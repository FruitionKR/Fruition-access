package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.service.DocumentInternalClient;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 소셜 신규 가입은 동의를 받아야 계정이 생기고, 약관 버전이 바뀌면 기존 회원이 다시 동의하는지 확인한다. */
@SpringBootTest(properties = {"app.legal.terms-version=2026-10-01", "app.legal.privacy-version=2026-10-01"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserConsentIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired OAuthExchangeCodeStore codeStore;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean EmailVerificationService emailVerificationService;

    @Test
    void socialSignupCreatesAccountOnlyAfterConsent() throws Exception {
        String providerUserId = "google-" + UUID.randomUUID();
        String email = providerUserId + "@example.com";
        String token = codeStore.issueSignupToken(
                new OAuthExchangeCodeStore.PendingSignup("google", providerUserId, email, "새 사용자"));

        signupConsent(token, false, "2026-10-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));
        signupConsent(token, true, "2026-01-01").andExpect(status().isBadRequest());
        assertThat(users.findAllByEmail(email)).isEmpty();

        signupConsent(token, true, "2026-10-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.consent_required").value(false));

        User user = users.findAllByEmail(email).get(0);
        assertThat(user.getProvider()).isEqualTo("google");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE user_id = ?", Integer.class,
                user.getId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM user_consents
                WHERE user_id = ? AND terms_version = '2026-10-01' AND age_confirmed AND marketing_opt_in
                """, Integer.class, user.getId())).isOne();

        signupConsent(token, true, "2026-10-01")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_TOKEN"));
    }

    @Test
    void emailSignupStoresConsentWithNewAccount() throws Exception {
        String email = "local-" + UUID.randomUUID() + "@example.com";

        // 동의 기록(JDBC)이 아직 flush되지 않은 users 행을 FK로 참조하지 않는지 실제 DB로 확인한다.
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1234","verification_token":"token",
                                 "age_confirmed":true,"terms_version":"2026-10-01"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        User user = users.findAllByEmail(email).get(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_consents WHERE user_id = ? AND NOT marketing_opt_in",
                Integer.class, user.getId())).isOne();
    }

    @Test
    void memberWithoutCurrentConsentIsAskedAgain() throws Exception {
        String userId = UUID.randomUUID().toString();
        users.saveAndFlush(new User(userId, userId + "@example.com", "local", "기존 회원", null));
        jdbc.update("""
                INSERT INTO user_consents(user_id, terms_version, privacy_version, age_confirmed, marketing_opt_in)
                VALUES (?, '2026-01-01', '2026-01-01', true, false)
                """, userId);
        String accessToken = "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");

        mockMvc.perform(get("/api/auth/me").header("Authorization", accessToken))
                .andExpect(jsonPath("$.consent_required").value(true));
        mockMvc.perform(post("/api/auth/me/consents").header("Authorization", accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age_confirmed\":true,\"terms_version\":\"2026-10-01\",\"marketing_opt_in\":false}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").header("Authorization", accessToken))
                .andExpect(jsonPath("$.consent_required").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_consents WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
    }

    private ResultActions signupConsent(String token, boolean ageConfirmed, String termsVersion) throws Exception {
        return mockMvc.perform(post("/api/auth/oauth/signup/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"signup_token":"%s","age_confirmed":%s,"terms_version":"%s","marketing_opt_in":true}
                        """.formatted(token, ageConfirmed, termsVersion)));
    }
}
