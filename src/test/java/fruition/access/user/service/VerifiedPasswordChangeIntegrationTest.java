package fruition.access.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserMfa;
import fruition.access.user.domain.UserMfaRecoveryCode;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.mail.EmailVerificationSender;
import fruition.access.user.mfa.MfaSecretCipher;
import fruition.access.user.repository.UserMfaRecoveryCodeRepository;
import fruition.access.user.repository.UserMfaRepository;
import fruition.access.user.repository.UserRepository;
import fruition.shared.security.JwtTokenProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 상태에서 계정 이메일 인증번호로 비밀번호를 바꾸는 흐름을 실제 HTTP·DB로 확인한다.
 * 받는 주소는 세션 사용자 이메일로 고정되고, 토큰은 그 이메일·password_change 목적에만 쓰인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class VerifiedPasswordChangeIntegrationTest {

    static final String OLD_PASSWORD = "old-password-1234";
    static final String NEW_PASSWORD = "new-password-5678";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired EmailVerificationService verificationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMfaRepository mfas;
    @Autowired UserMfaRecoveryCodeRepository recoveryCodes;
    @Autowired MfaSecretCipher cipher;
    @MockitoBean EmailVerificationSender sender;

    @Test
    void sendsCodeToSessionEmail_thenChangesPasswordAndKeepsOnlyCurrentSession() throws Exception {
        User user = localUser();
        String current = refreshToken(user.getId());
        String other = refreshToken(user.getId());

        String verificationId = requestVerification(user)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.verification_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(verificationId).get("verification_id").asText();
        assertThat(jdbc.queryForObject("SELECT email || '/' || purpose FROM email_verifications WHERE id = ?",
                String.class, id)).isEqualTo(user.getEmail() + "/password_change");

        String token = confirm(id, user.getEmail(), "password_change");
        change(user, token, current).andExpect(status().isNoContent());

        assertThat(passwordMatches(user, NEW_PASSWORD)).isTrue();
        assertThat(revoked(current)).isFalse();
        assertThat(revoked(other)).isTrue();
        // 1회용: 같은 토큰으로 다시 바꿀 수 없다.
        change(user, token, current).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_TOKEN"));
    }

    @Test
    void mfaAccount_requiresValidMfaCodeWithoutBurningToken() throws Exception {
        User user = localUser();
        String recoveryCode = enableMfa(user);
        String token = passwordChangeToken(user);

        change(user, token, null, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        change(user, token, null, "not-a-code").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        assertThat(passwordMatches(user, OLD_PASSWORD)).isTrue();

        // 거절된 요청은 토큰을 소비하지 않아 같은 토큰으로 다시 시도할 수 있다.
        change(user, token, null, recoveryCode).andExpect(status().isNoContent());
        assertThat(passwordMatches(user, NEW_PASSWORD)).isTrue();
    }

    @Test
    void accountWithoutMfa_ignoresMfaCode() throws Exception {
        User user = localUser();

        change(user, passwordChangeToken(user), null, "not-a-code").andExpect(status().isNoContent());
        assertThat(passwordMatches(user, NEW_PASSWORD)).isTrue();
    }

    @Test
    void rejectsTokenIssuedToAnotherAccount() throws Exception {
        User attacker = localUser();
        User victim = localUser();
        String id = objectMapper.readTree(requestVerification(attacker).andReturn().getResponse().getContentAsString())
                .get("verification_id").asText();
        String attackerToken = confirm(id, attacker.getEmail(), "password_change");

        change(victim, attackerToken, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_TOKEN"));
        assertThat(passwordMatches(victim, OLD_PASSWORD)).isTrue();
    }

    @Test
    void rejectsPasswordResetTokenForSameEmail() throws Exception {
        User user = localUser();
        String id = verificationService.request(new EmailVerificationRequest(user.getEmail(), "password_reset"))
                .verificationId();

        change(user, confirm(id, user.getEmail(), "password_reset"), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_TOKEN"));
        assertThat(passwordMatches(user, OLD_PASSWORD)).isTrue();
    }

    @Test
    void passwordChangeTokenCannotBeUsedForAnonymousPasswordReset() throws Exception {
        User user = localUser();
        String id = objectMapper.readTree(requestVerification(user).andReturn().getResponse().getContentAsString())
                .get("verification_id").asText();
        String token = confirm(id, user.getEmail(), "password_change");

        mockMvc.perform(post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", user.getEmail(), "new_password", NEW_PASSWORD, "verification_token", token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_TOKEN"));
        assertThat(passwordMatches(user, OLD_PASSWORD)).isTrue();
    }

    @Test
    void publicVerificationApiDoesNotIssuePasswordChangeCodes() throws Exception {
        mockMvc.perform(post("/api/auth/email-verifications").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "someone@example.com", "purpose", "password_change")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oauthOnlyAccountIsRejected() throws Exception {
        String id = "u_" + UUID.randomUUID().toString().replace("-", "");
        User user = users.saveAndFlush(new User(id, id + "@example.com", "google", "사용자", null));

        requestVerification(user).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_LOGIN_UNAVAILABLE"));
        change(user, "any-token", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_LOGIN_UNAVAILABLE"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/me/password/verification")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/auth/me/password/verified").contentType(MediaType.APPLICATION_JSON)
                        .content(json("new_password", NEW_PASSWORD, "verification_token", "t")))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions requestVerification(User user) throws Exception {
        return mockMvc.perform(post("/api/auth/me/password/verification").header("Authorization", bearer(user)));
    }

    private ResultActions change(User user, String token, String refreshToken) throws Exception {
        return change(user, token, refreshToken, null);
    }

    private ResultActions change(User user, String token, String refreshToken, String mfaCode) throws Exception {
        String body = mfaCode == null
                ? json("new_password", NEW_PASSWORD, "verification_token", token)
                : json("new_password", NEW_PASSWORD, "verification_token", token, "mfa_code", mfaCode);
        var request = put("/api/auth/me/password/verified").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (refreshToken != null) {
            request.cookie(new Cookie("fruition_refresh_token", refreshToken));
        }
        return mockMvc.perform(request);
    }

    private String passwordChangeToken(User user) throws Exception {
        String id = objectMapper.readTree(requestVerification(user).andReturn().getResponse().getContentAsString())
                .get("verification_id").asText();
        return confirm(id, user.getEmail(), "password_change");
    }

    private String enableMfa(User user) {
        var encrypted = cipher.encrypt(new byte[20]);
        var mfa = new UserMfa(user.getId(), encrypted.ciphertext(), encrypted.nonce());
        mfa.activate(Instant.now().getEpochSecond() / 30 - 2);
        mfas.saveAndFlush(mfa);
        String code = "recovery-" + UUID.randomUUID();
        recoveryCodes.saveAndFlush(new UserMfaRecoveryCode(user.getId(), OpaqueTokens.sha256(code)));
        return code;
    }

    private String confirm(String verificationId, String email, String purpose) {
        return verificationService.confirm(verificationId, new VerificationConfirmRequest(sentCode(email, purpose)))
                .verificationToken();
    }

    private String sentCode(String email, String purpose) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), eq(purpose), code.capture());
        return code.getValue();
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), Instant.now());
    }

    private User localUser() {
        String id = "u_" + UUID.randomUUID().toString().replace("-", "");
        return users.saveAndFlush(new User(id, id + "@example.com", User.PROVIDER_LOCAL, "사용자",
                passwordEncoder.encode(OLD_PASSWORD)));
    }

    private boolean passwordMatches(User user, String password) {
        return passwordEncoder.matches(password, users.findById(user.getId()).orElseThrow().getPasswordHash());
    }

    private String refreshToken(String userId) throws Exception {
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO user_refresh_tokens(created_at, expires_at, token_hash, user_id) "
                + "VALUES (now(), now() + interval '1 day', ?, ?)", sha256(token), userId);
        return token;
    }

    private boolean revoked(String token) throws Exception {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM user_refresh_tokens WHERE token_hash = ?", Boolean.class, sha256(token)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String json(String... keyValues) throws Exception {
        var node = objectMapper.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return objectMapper.writeValueAsString(node);
    }
}
