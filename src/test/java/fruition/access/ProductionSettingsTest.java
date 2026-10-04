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
                .withProperty("app.auth.mfa.encryption-key", "+5w4SOzUyS26oJh28MKasrE5rYZaq8IBE7pCrJVXLBE=")
                .withProperty("app.workspace.invitation.accept-url", "https://app.example.com/invitations")
                .withProperty("app.jwt.secret", "p3LqS6vH9zB2nK5wR8tY1cX4mJ7dG0aQ")
                .withProperty("app.auth.refresh-cookie-secure", "true")
                .withProperty("app.internal.callback-token", "Zr8wQ2nV5kT1xL7cH4bM9pS3dF6gJ0aY");
    }

    @Test
    void acceptsCompleteProductionSettings() {
        assertDoesNotThrow(() -> new ProductionSettings(valid()));
    }

    @Test
    void rejectsEachMissingRequiredSetting() {
        for (String key : new String[] {"spring.mail.host", "spring.mail.port", "spring.mail.username",
                "spring.mail.password", "app.auth.email-verification.from", "app.auth.mfa.encryption-key",
                "app.workspace.invitation.accept-url", "app.jwt.secret", "app.auth.refresh-cookie-secure",
                "app.internal.callback-token"}) {
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

    /**
     * 커밋된 테스트 전용 값으로는 운영이 뜨지 못해야 한다. 길이 게이트만 두면 git 이력에 있는
     * 36바이트 테스트 키가 그대로 통과해, 고치려던 결함(공개된 키로 토큰 서명)이 되돌아온다.
     * 리터럴을 하나씩 denylist에 넣는 방식은 계속 늘어나므로 marker 규칙으로 막는다.
     */
    @Test
    void rejectsSecretsCarryingInsecureTestMarker() {
        for (String key : new String[] {"app.jwt.secret", "spring.mail.password"}) {
            MockEnvironment environment = valid();
            environment.setProperty(key, "value-INSECURE-NOT-FOR-PRODUCTION-padding-to-32-bytes");
            assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(environment), key);
        }
    }

    /** build.gradle과 application-test.properties가 실제로 쓰는 값이 운영에서 거부되는지 고정한다. */
    @Test
    void rejectsTheCommittedTestJwtSecret() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                .withProperty("app.jwt.secret", "jwt-secret-INSECURE-NOT-FOR-PRODUCTION-test")));
    }

    /**
     * build.gradle·application-test.properties·.env.example에 커밋된 INTERNAL_CALLBACK_TOKEN은
     * 표식을 달고 있지만, 표식 검사 대상 목록에 없으면 그 값 그대로 운영이 뜬다.
     */
    @Test
    void rejectsTheCommittedTestCallbackToken() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                .withProperty("app.internal.callback-token", "callback-token-INSECURE-NOT-FOR-PRODUCTION")));
    }

    /**
     * MFA 키는 base64 고정 길이라 표식을 넣을 수 없다. 길이만 검사하면 build.gradle·
     * application-test.properties에 커밋된 0바이트 키(AAAA...=)가 그대로 운영을 통과해,
     * 공개된 키로 모든 TOTP secret을 암호화하게 된다. 모든 바이트가 같은 키는 거부한다.
     */
    @Test
    void rejectsTheCommittedAllZeroAndOtherUniformMfaKeys() {
        for (String value : new String[] {"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="}) {
            assertThrows(IllegalArgumentException.class, () -> new ProductionSettings(valid()
                    .withProperty("app.auth.mfa.encryption-key", value)), value);
        }
    }
}
