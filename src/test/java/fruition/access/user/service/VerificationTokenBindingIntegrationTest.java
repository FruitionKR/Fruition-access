package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.domain.User;
import fruition.access.user.dto.EmailChangeRequest;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.dto.SignupRequest;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인증번호로 받은 verification_token이 발급받은 이메일·목적에 묶이고, 1회용이며 만료되는지
 * 실제 트랜잭션·DB로 확인한다. A 메일함으로 받은 토큰으로 B 계정을 바꿀 수 있으면 계정 탈취다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.auth.email-verification.dev-fixed-code=246802")
class VerificationTokenBindingIntegrationTest {

    static final String CODE = "246802";
    static final String OLD_PASSWORD = "old-password-1234";
    static final String NEW_PASSWORD = "new-password-5678";

    @Autowired EmailVerificationService verificationService;
    @Autowired AuthService authService;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Value("${app.legal.terms-version}") String termsVersion;

    @Test
    void passwordReset_tokenIssuedForOtherEmail_isRejectedAndVictimPasswordUnchanged() {
        String attacker = localUser();
        String victim = localUser();
        String attackerToken = token(attacker, "password_reset");

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequest(victim, NEW_PASSWORD, attackerToken)))
                .isInstanceOf(InvalidVerificationTokenException.class);

        assertThat(passwordMatches(victim, OLD_PASSWORD)).isTrue();
        // 거절된 시도는 토큰을 소비하지 않으므로 발급받은 본인 이메일에는 그대로 쓸 수 있다.
        authService.resetPassword(new PasswordResetRequest(attacker, NEW_PASSWORD, attackerToken));
        assertThat(passwordMatches(attacker, NEW_PASSWORD)).isTrue();
    }

    @Test
    void passwordReset_emailCaseAndSpacesAreNormalizedBeforeBinding() {
        String email = localUser();
        String token = token(email, "password_reset");

        authService.resetPassword(new PasswordResetRequest("  " + email.toUpperCase() + " ", NEW_PASSWORD, token));

        assertThat(passwordMatches(email, NEW_PASSWORD)).isTrue();
    }

    @Test
    void passwordReset_tokenIsSingleUse() {
        String email = localUser();
        String token = token(email, "password_reset");
        authService.resetPassword(new PasswordResetRequest(email, NEW_PASSWORD, token));

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequest(email, "another-password-9", token)))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(passwordMatches(email, NEW_PASSWORD)).isTrue();
    }

    @Test
    void passwordReset_expiredToken_isRejected() {
        String email = localUser();
        String token = token(email, "password_reset");
        jdbc.update("UPDATE email_verifications SET token_expires_at = now() - interval '1 second' WHERE email = ?", email);

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequest(email, NEW_PASSWORD, token)))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(passwordMatches(email, OLD_PASSWORD)).isTrue();
    }

    @Test
    void passwordReset_tokenForOtherPurpose_isRejected() {
        String email = localUser();
        String signupToken = token(uniqueEmail(), "signup");
        String emailChangeToken = token(email, "email_change");

        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequest(email, NEW_PASSWORD, signupToken)))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThatThrownBy(() -> authService.resetPassword(new PasswordResetRequest(email, NEW_PASSWORD, emailChangeToken)))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(passwordMatches(email, OLD_PASSWORD)).isTrue();
    }

    @Test
    void signup_tokenForOtherEmailOrPurpose_isRejected() {
        String verified = uniqueEmail();
        String other = uniqueEmail();
        String signupToken = token(verified, "signup");
        String resetToken = token(other, "password_reset");

        assertThatThrownBy(() -> userService.signup(signup(other, signupToken)))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThatThrownBy(() -> userService.signup(signup(other, resetToken)))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(users.findByEmailAndProvider(other, User.PROVIDER_LOCAL)).isEmpty();
    }

    @Test
    void emailChange_tokenForOtherEmailOrPurpose_isRejected() {
        String owner = localUser();
        String ownerId = users.findByEmailAndProvider(owner, User.PROVIDER_LOCAL).orElseThrow().getId();
        String verified = uniqueEmail();
        String target = uniqueEmail();
        String emailChangeToken = token(verified, "email_change");
        String resetToken = token(target, "password_reset");

        assertThatThrownBy(() -> authService.changeEmail(ownerId, new EmailChangeRequest(target, emailChangeToken), null))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThatThrownBy(() -> authService.changeEmail(ownerId, new EmailChangeRequest(target, resetToken), null))
                .isInstanceOf(InvalidVerificationTokenException.class);
        assertThat(users.findById(ownerId).orElseThrow().getEmail()).isEqualTo(owner);
    }

    private String token(String email, String purpose) {
        String verificationId = verificationService.request(new EmailVerificationRequest(email, purpose)).verificationId();
        return verificationService.confirm(verificationId, new VerificationConfirmRequest(CODE)).verificationToken();
    }

    private String localUser() {
        String email = uniqueEmail();
        users.saveAndFlush(new User("u_" + UUID.randomUUID().toString().replace("-", ""), email,
                User.PROVIDER_LOCAL, "사용자", passwordEncoder.encode(OLD_PASSWORD)));
        return email;
    }

    private boolean passwordMatches(String email, String password) {
        User user = users.findByEmailAndProvider(email, User.PROVIDER_LOCAL).orElseThrow();
        return passwordEncoder.matches(password, user.getPasswordHash());
    }

    private SignupRequest signup(String email, String token) {
        return new SignupRequest(email, "password-1234", "가입자", token, true, termsVersion, false);
    }

    private static String uniqueEmail() {
        return "bind-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }
}
