package fruition.access.security.oauth;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.service.DocumentInternalClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 데스크톱 OAuth 로그인: 시작 요청의 PKCE 검사와, 교환 코드·가입 대기 토큰의 code_verifier 검증. */
@SpringBootTest(properties = {"app.legal.terms-version=2026-10-01", "app.legal.privacy-version=2026-10-01"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DesktopOAuthIntegrationTest {

    /** RFC 7636 부록 B의 예시 값. */
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired MockMvc mockMvc;
    @Autowired OAuthExchangeCodeStore codeStore;
    @Autowired UserRepository users;
    @MockitoBean DocumentInternalClient documentClient;

    @Test
    void desktopStart_requiresS256Challenge() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google")
                        .param("client", "desktop").param("code_challenge", CHALLENGE)
                        .param("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/oauth2/authorization/google")
                        .param("client", "desktop").param("code_challenge", CHALLENGE)
                        .param("code_challenge_method", "plain"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/oauth2/authorization/google")
                        .param("client", "desktop").param("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void desktopCode_isExchangedOnlyWithMatchingVerifier() throws Exception {
        String userId = UUID.randomUUID().toString();
        users.saveAndFlush(new User(userId, userId + "@example.com", "google", "데스크톱 사용자", null));

        exchange(codeStore.issue(userId, CHALLENGE), VERIFIER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists());

        exchange(codeStore.issue(userId, CHALLENGE), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_OAUTH_CODE"));

        // 틀린 verifier로 시도한 코드는 소비돼 올바른 verifier로도 다시 쓸 수 없다.
        String code = codeStore.issue(userId, CHALLENGE);
        exchange(code, "wrong-verifier-wrong-verifier-wrong-verifier").andExpect(status().isUnauthorized());
        exchange(code, VERIFIER).andExpect(status().isUnauthorized());

        // 웹 로그인 코드는 verifier 없이 지금처럼 교환된다.
        exchange(codeStore.issue(userId, null), null).andExpect(status().isOk());
    }

    @Test
    void desktopSignupToken_requiresMatchingVerifier() throws Exception {
        String providerUserId = "google-" + UUID.randomUUID();
        var pending = new OAuthExchangeCodeStore.PendingSignup(
                "google", providerUserId, providerUserId + "@example.com", "새 사용자", CHALLENGE);

        signupConsent(codeStore.issueSignupToken(pending), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_TOKEN"));
        signupConsent(codeStore.issueSignupToken(pending), VERIFIER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists());
    }

    private ResultActions exchange(String code, String verifier) throws Exception {
        return mockMvc.perform(post("/api/auth/oauth/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(verifier == null ? "{\"code\":\"%s\"}".formatted(code)
                        : "{\"code\":\"%s\",\"code_verifier\":\"%s\"}".formatted(code, verifier)));
    }

    private ResultActions signupConsent(String token, String verifier) throws Exception {
        return mockMvc.perform(post("/api/auth/oauth/signup/consent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"signup_token":"%s","age_confirmed":true,"terms_version":"2026-10-01",
                         "marketing_opt_in":false%s}
                        """.formatted(token, verifier == null ? "" : ",\"code_verifier\":\"" + verifier + "\"")));
    }
}
