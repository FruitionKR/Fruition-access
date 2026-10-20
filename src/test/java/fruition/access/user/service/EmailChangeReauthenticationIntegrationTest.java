package fruition.access.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserMfa;
import fruition.access.user.domain.UserMfaRecoveryCode;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.mail.EmailChangeNotificationSender;
import fruition.access.user.mail.EmailVerificationSender;
import fruition.access.user.mfa.MfaSecretCipher;
import fruition.access.user.repository.UserMfaRecoveryCodeRepository;
import fruition.access.user.repository.UserMfaRepository;
import fruition.access.user.repository.UserRepository;
import fruition.shared.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이메일 변경은 새 메일함 인증에 더해 본인 재확인을 요구한다. 세션만 탈취한 사람이 이메일을 바꿔
 * 비밀번호 재설정까지 가져가지 못하게 하기 위해서다. 재확인에 실패하면 새 이메일 토큰은 남아 있어야 하고,
 * 성공하면 커밋 뒤 기존 이메일로 알림이 간다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailChangeReauthenticationIntegrationTest {

    static final String PASSWORD = "current-password-1";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository users;
    @Autowired UserMfaRepository mfas;
    @Autowired UserMfaRecoveryCodeRepository recoveryCodes;
    @Autowired MfaSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired EmailVerificationService verificationService;
    @MockitoBean EmailChangeNotificationSender notificationSender;
    @MockitoBean EmailVerificationSender sender;

    @Test
    void localAccount_correctPassword_changesEmailAndNotifiesPreviousAddress() throws Exception {
        User user = localUser();
        String newEmail = "newowner-" + shortId() + "@example.com";

        change(user, Instant.now(), body(newEmail, token(newEmail), PASSWORD, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail));

        assertThat(emailOf(user)).isEqualTo(newEmail);
        String masked = newEmail.substring(0, 2) + "*".repeat(newEmail.indexOf('@') - 2) + "@example.com";
        verify(notificationSender).send(eq(user.getEmail()), eq(masked), any(Instant.class));
    }

    @Test
    void localAccount_wrongOrMissingPassword_isRejectedWithoutConsumingToken() throws Exception {
        User user = localUser();
        String newEmail = uniqueEmail();
        String token = token(newEmail);

        change(user, Instant.now(), body(newEmail, token, "wrong-password", null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        change(user, Instant.now(), body(newEmail, token, null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        assertThat(emailOf(user)).isEqualTo(user.getEmail());
        verify(notificationSender, never()).send(anyString(), anyString(), any());

        // 거절된 요청은 토큰을 소비하지 않았으므로 같은 토큰으로 다시 시도할 수 있다.
        change(user, Instant.now(), body(newEmail, token, PASSWORD, null)).andExpect(status().isOk());
        assertThat(emailOf(user)).isEqualTo(newEmail);
    }

    @Test
    void repeatedWrongPasswords_areRateLimited() throws Exception {
        User user = localUser();
        String newEmail = uniqueEmail();
        String token = token(newEmail);
        for (int i = 0; i < 5; i++) {
            change(user, Instant.now(), body(newEmail, token, "wrong-password", null))
                    .andExpect(status().isUnauthorized());
        }

        change(user, Instant.now(), body(newEmail, token, PASSWORD, null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_RATE_LIMITED"));
        assertThat(emailOf(user)).isEqualTo(user.getEmail());
    }

    @Test
    void socialAccount_requiresRecentDirectLogin() throws Exception {
        User user = socialUser();
        String newEmail = uniqueEmail();
        String token = token(newEmail);

        // refresh로 받은 토큰(auth_time 없음)과 10분이 지난 로그인은 거절한다.
        change(user, null, body(newEmail, token, null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
        change(user, Instant.now().minus(Duration.ofMinutes(11)), body(newEmail, token, null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
        assertThat(emailOf(user)).isEqualTo(user.getEmail());

        change(user, Instant.now().minus(Duration.ofMinutes(2)), body(newEmail, token, null, null))
                .andExpect(status().isOk());
        assertThat(emailOf(user)).isEqualTo(newEmail);
    }

    @Test
    void mfaAccount_requiresValidMfaCode() throws Exception {
        User user = localUser();
        String recoveryCode = enableMfa(user);
        String newEmail = uniqueEmail();
        String token = token(newEmail);

        change(user, Instant.now(), body(newEmail, token, PASSWORD, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        change(user, Instant.now(), body(newEmail, token, PASSWORD, "not-a-code"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_MFA_CODE"));
        assertThat(emailOf(user)).isEqualTo(user.getEmail());

        change(user, Instant.now(), body(newEmail, token, PASSWORD, recoveryCode)).andExpect(status().isOk());
        assertThat(emailOf(user)).isEqualTo(newEmail);
    }

    @Test
    void notificationFailure_doesNotRollBackEmailChange() throws Exception {
        User user = localUser();
        String newEmail = uniqueEmail();
        doThrow(new IllegalStateException("smtp down")).when(notificationSender).send(anyString(), anyString(), any());

        change(user, Instant.now(), body(newEmail, token(newEmail), PASSWORD, null)).andExpect(status().isOk());

        assertThat(emailOf(user)).isEqualTo(newEmail);
        verify(notificationSender).send(eq(user.getEmail()), anyString(), any());
    }

    @Test
    void failedChange_sendsNoNotification() throws Exception {
        User user = localUser();
        String newEmail = uniqueEmail();

        change(user, Instant.now(), body(newEmail, "not-a-real-token", PASSWORD, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_TOKEN"));

        verify(notificationSender, never()).send(anyString(), anyString(), any());
    }

    @Test
    void localAccount_toAddressOfGoogleAccount_isRejectedWithoutConsumingToken() throws Exception {
        assertAddressInUseRejected("google");
    }

    @Test
    void localAccount_toAddressOfOtherLocalAccount_isRejectedWithoutConsumingToken() throws Exception {
        assertAddressInUseRejected(User.PROVIDER_LOCAL);
    }

    @Test
    void emailChangeCode_forAddressInUse_isNotSent() {
        String taken = socialUser().getEmail();

        assertThatThrownBy(() -> verificationService.request(new EmailVerificationRequest(taken, "email_change")))
                .isInstanceOf(DuplicateEmailException.class);
        verify(sender, never()).send(eq(taken), anyString(), anyString());
    }

    /**
     * 토큰을 받은 뒤 다른 계정이 그 주소를 차지하면 provider와 상관없이 409로 거절한다.
     * 거절은 토큰을 소비하지 않으므로, 주소가 다시 비면 같은 토큰으로 바꿀 수 있다.
     */
    private void assertAddressInUseRejected(String occupantProvider) throws Exception {
        User user = localUser();
        String newEmail = uniqueEmail();
        String token = token(newEmail);
        String occupantId = "u_" + shortId();
        users.saveAndFlush(new User(occupantId, newEmail, occupantProvider, "다른 사용자", null));

        change(user, Instant.now(), body(newEmail, token, PASSWORD, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
        assertThat(emailOf(user)).isEqualTo(user.getEmail());
        verify(notificationSender, never()).send(anyString(), anyString(), any());

        User occupant = users.findById(occupantId).orElseThrow();
        occupant.changeEmail(uniqueEmail());
        users.saveAndFlush(occupant);
        change(user, Instant.now(), body(newEmail, token, PASSWORD, null)).andExpect(status().isOk());
        assertThat(emailOf(user)).isEqualTo(newEmail);
    }

    private ResultActions change(User user, Instant authTime, String body) throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), authTime);
        return mockMvc.perform(put("/api/auth/me/email").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(String newEmail, String token, String password, String mfaCode) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("new_email", newEmail);
        body.put("verification_token", token);
        if (password != null) body.put("current_password", password);
        if (mfaCode != null) body.put("mfa_code", mfaCode);
        return objectMapper.writeValueAsString(body);
    }

    private String token(String email) {
        String id = verificationService.request(new EmailVerificationRequest(email, "email_change")).verificationId();
        return verificationService.confirm(id, new VerificationConfirmRequest(sentCode(email, "email_change")))
                .verificationToken();
    }

    private String sentCode(String email, String purpose) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(email), eq(purpose), code.capture());
        return code.getValue();
    }

    private String enableMfa(User user) {
        var encrypted = cipher.encrypt(new byte[20]);
        var mfa = new UserMfa(user.getId(), encrypted.ciphertext(), encrypted.nonce());
        mfa.activate(Instant.now().getEpochSecond() / 30 - 2);
        mfas.saveAndFlush(mfa);
        String code = "recovery-" + shortId();
        recoveryCodes.saveAndFlush(new UserMfaRecoveryCode(user.getId(), OpaqueTokens.sha256(code)));
        return code;
    }

    private User localUser() {
        String id = "u_" + shortId();
        return users.saveAndFlush(new User(id, id + "@example.com", User.PROVIDER_LOCAL, "사용자",
                passwordEncoder.encode(PASSWORD)));
    }

    private User socialUser() {
        String id = "u_" + shortId();
        return users.saveAndFlush(new User(id, id + "@example.com", "google", "사용자", null));
    }

    private String emailOf(User user) {
        return users.findById(user.getId()).orElseThrow().getEmail();
    }

    private static String uniqueEmail() {
        return "changed-" + shortId() + "@example.com";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
