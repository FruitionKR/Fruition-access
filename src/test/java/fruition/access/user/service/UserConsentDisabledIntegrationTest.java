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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 약관 확정 전(app.legal.consent-required=false)에는 동의 없이 가입되고 동의 기록을 남기지 않는지 확인한다. */
@SpringBootTest(properties = {"app.legal.terms-version=2026-10-01", "app.legal.privacy-version=2026-10-01",
        "app.legal.consent-required=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UserConsentDisabledIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired OAuthExchangeCodeStore codeStore;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentInternalClient documentClient;
    @MockitoBean EmailVerificationService emailVerificationService;

    @Test
    void emailSignupWithoutConsentFieldsCreatesAccountWithoutConsentRecord() throws Exception {
        String email = "local-" + UUID.randomUUID() + "@example.com";

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1234","verification_token":"token"}
                                """.formatted(email)))
                .andExpect(status().isCreated());

        User user = users.findAllByEmail(email).get(0);
        assertThat(consentRows(user.getId())).isZero();
        String accessToken = "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), email);
        mockMvc.perform(get("/api/auth/me").header("Authorization", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consent_required").value(false));
    }

    @Test
    void socialSignupCompletesWithOnlySignupToken() throws Exception {
        String providerUserId = "google-" + UUID.randomUUID();
        String email = providerUserId + "@example.com";
        String token = codeStore.issueSignupToken(
                new OAuthExchangeCodeStore.PendingSignup("google", providerUserId, email, "새 사용자", null));

        mockMvc.perform(post("/api/auth/oauth/signup/consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signup_token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.consent_required").value(false));

        User user = users.findAllByEmail(email).get(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_members WHERE user_id = ?", Integer.class,
                user.getId())).isOne();
        assertThat(consentRows(user.getId())).isZero();
    }

    @Test
    void consentFieldsSentAnywayAreNotRecorded() throws Exception {
        String userId = UUID.randomUUID().toString();
        users.saveAndFlush(new User(userId, userId + "@example.com", "local", "기존 회원", null));
        String accessToken = "Bearer " + jwtTokenProvider.generateAccessToken(userId, userId + "@example.com");

        mockMvc.perform(get("/api/auth/me").header("Authorization", accessToken))
                .andExpect(jsonPath("$.consent_required").value(false));
        mockMvc.perform(post("/api/auth/me/consents").header("Authorization", accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age_confirmed\":true,\"terms_version\":\"2026-10-01\",\"marketing_opt_in\":true}"))
                .andExpect(status().isNoContent());
        assertThat(consentRows(userId)).isZero();
    }

    private int consentRows(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM user_consents WHERE user_id = ?", Integer.class, userId);
    }
}
