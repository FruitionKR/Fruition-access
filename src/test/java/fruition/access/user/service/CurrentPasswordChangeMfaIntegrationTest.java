package fruition.access.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserMfa;
import fruition.access.user.domain.UserMfaRecoveryCode;
import fruition.access.user.mfa.MfaSecretCipher;
import fruition.access.user.repository.UserMfaRecoveryCodeRepository;
import fruition.access.user.repository.UserMfaRepository;
import fruition.access.user.repository.UserRepository;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 현재 비밀번호로 비밀번호를 바꾸는 경로(PUT /api/auth/me/password)의 MFA 확인을 실제 HTTP·DB·Redis로 확인한다.
 * 검사 순서는 현재 비밀번호 → MFA 코드이고, 비밀번호 변경 실패 예산은 현재 비밀번호 실패만 센다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CurrentPasswordChangeMfaIntegrationTest {

    static final String OLD_PASSWORD = "old-password-1234";
    static final String NEW_PASSWORD = "new-password-5678";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserMfaRepository mfas;
    @Autowired UserMfaRecoveryCodeRepository recoveryCodes;
    @Autowired MfaSecretCipher cipher;
    @Autowired StringRedisTemplate redis;

    @Test
    void mfaAccount_requiresValidMfaCode() throws Exception {
        User user = localUser();
        String recoveryCode = enableMfa(user);

        change(user, OLD_PASSWORD, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        change(user, OLD_PASSWORD, "not-a-code").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        assertThat(passwordMatches(user, OLD_PASSWORD)).isTrue();
        // MFA 실패는 현재 비밀번호 실패 예산을 쓰지 않는다.
        assertThat(passwordChangeFailureKeys(user)).isEmpty();

        change(user, OLD_PASSWORD, recoveryCode).andExpect(status().isNoContent());
        assertThat(passwordMatches(user, NEW_PASSWORD)).isTrue();
    }

    @Test
    void mfaAccount_wrongCurrentPasswordIsReportedBeforeMfa() throws Exception {
        User user = localUser();
        enableMfa(user);

        change(user, "wrong-password-1", null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        assertThat(passwordMatches(user, OLD_PASSWORD)).isTrue();
        assertThat(passwordChangeFailureKeys(user)).hasSize(1);
    }

    @Test
    void accountWithoutMfa_changesWithOrWithoutMfaCode() throws Exception {
        User withoutCode = localUser();
        change(withoutCode, OLD_PASSWORD, null).andExpect(status().isNoContent());
        assertThat(passwordMatches(withoutCode, NEW_PASSWORD)).isTrue();

        User withCode = localUser();
        change(withCode, OLD_PASSWORD, "not-a-code").andExpect(status().isNoContent());
        assertThat(passwordMatches(withCode, NEW_PASSWORD)).isTrue();
    }

    private ResultActions change(User user, String currentPassword, String mfaCode) throws Exception {
        var node = objectMapper.createObjectNode()
                .put("current_password", currentPassword)
                .put("new_password", NEW_PASSWORD);
        if (mfaCode != null) {
            node.put("mfa_code", mfaCode);
        }
        return mockMvc.perform(put("/api/auth/me/password").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(node)));
    }

    private Set<String> passwordChangeFailureKeys(User user) {
        return redis.keys("auth:password-change:fail:" + OpaqueTokens.sha256(user.getId()) + ":*");
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
}
