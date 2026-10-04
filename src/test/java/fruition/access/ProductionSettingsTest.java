package fruition.access;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSettingsTest {
    private MockEnvironment valid() {
        return new MockEnvironment()
                .withProperty("spring.mail.host", "smtp.example.com")
                .withProperty("spring.mail.port", "587")
                .withProperty("spring.mail.username", "test-user")
                .withProperty("spring.mail.password", "test-password")
                .withProperty("app.auth.email-verification.from", "noreply@example.com")
                .withProperty("app.auth.mfa.encryption-key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .withProperty("app.workspace.invitation.accept-url", "https://app.example.com/invitations")
                .withProperty("app.jwt.secret", "p3LqS6vH9zB2nK5wR8tY1cX4mJ7dG0aQ")
                .withProperty("app.auth.refresh-cookie-secure", "true");
    }

    @Test
    void acceptsCompleteProductionSettings() {
        assertDoesNotThrow(() -> new ProductionSettings(valid()));
    }

    @Test
    void rejectsEachMissingRequiredSetting() {
        for (String key : new String[] {"spring.mail.host", "spring.mail.port", "spring.mail.username",
                "spring.mail.password", "app.auth.email-verification.from", "app.auth.mfa.encryption-key",
                "app.workspace.invitation.accept-url", "app.jwt.secret", "app.auth.refresh-cookie-secure"}) {
            MockEnvironment environment = valid();
            environment.setProperty(key, "");
            assertThrows(RuntimeException.class, () -> new ProductionSettings(environment), key);
        }
    }

    @Test
    void rejectsInvalidKeyInvitationAndFixedCode() {
        for (String value : new String[] {"http://app.example.com/invitations", "https://localhost/invitations", "https://REPLACE_ME_APP_DOMAIN/invitations"}) {
            assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                    .withProperty("app.workspace.invitation.accept-url", value)));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                .withProperty("app.auth.mfa.encryption-key", "dGVzdA==")));
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                .withProperty("app.auth.email-verification.dev-fixed-code", "123456")));
    }

    /**
     * JWT_SECRET이 빠지면 git에 공개된 개발 기본값으로 모든 access token에 서명하게 되고,
     * issuer·audience도 커밋된 기본값이라 토큰을 위조할 수 있다. 운영에서는 기동을 막는다.
     */
    @Test
    void rejectsCommittedDefaultOrTooShortJwtSecret() {
        for (String value : new String[] {"dev-only-jwt-secret-change-me-please-32bytes-min", "too-short-secret"}) {
            assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                    .withProperty("app.jwt.secret", value)), value);
        }
    }

    /** refresh 쿠키가 Secure 없이 나가면 14일 refresh token이 평문 HTTP로 노출된다. */
    @Test
    void rejectsInsecureRefreshCookie() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                .withProperty("app.auth.refresh-cookie-secure", "false")));
    }
}
