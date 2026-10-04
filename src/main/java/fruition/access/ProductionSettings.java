package fruition.access;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** 운영에서 메일 stub나 localhost 초대 링크로 기동하지 않도록 설정을 검증한다. */
@Configuration
@Profile("production")
public class ProductionSettings {

    /** application.properties에서 제거했지만 손으로 다시 넣는 경우까지 막는다. */
    private static final String COMMITTED_DEV_JWT_SECRET = "dev-only-jwt-secret-change-me-please-32bytes-min";

    /**
     * 커밋해 두는 테스트·개발 전용 비밀값에 반드시 넣는 표식.
     *
     * <p>길이나 형식만 검사하면 git 이력에 있는 값이 그대로 운영을 통과한다. 리터럴을 하나씩
     * denylist에 넣는 방식은 값을 새로 추가할 때마다 같이 늘려야 하고, 빠뜨리면 조용히 뚫린다.
     * 대신 "커밋되는 비밀값은 이 표식을 달고, 표식이 붙은 값은 운영에서 거부한다"는 규칙 하나로
     * 과거·미래의 모든 커밋된 값을 한 번에 막는다.
     */
    private static final String INSECURE_MARKER = "INSECURE-NOT-FOR-PRODUCTION";

    public ProductionSettings(Environment environment) {
        for (String key : new String[] {"spring.mail.host", "spring.mail.username", "spring.mail.password",
                "app.auth.email-verification.from", "app.auth.mfa.encryption-key",
                "app.workspace.invitation.accept-url", "app.jwt.secret",
                "app.auth.refresh-cookie-secure"}) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank() || value.contains("REPLACE_ME")) {
                throw new IllegalArgumentException("필수 운영 설정 누락: " + key);
            }
            // 커밋된 테스트 전용 값으로 운영이 뜨면 공개된 비밀값을 쓰는 것과 같다.
            if (value.contains(INSECURE_MARKER)) {
                throw new IllegalArgumentException("커밋된 테스트 전용 값은 운영에서 쓸 수 없습니다: " + key);
            }
        }
        String key = environment.getRequiredProperty("app.auth.mfa.encryption-key");
        if (Base64.getDecoder().decode(key).length != 32) {
            throw new IllegalArgumentException("MFA 키는 base64로 인코딩한 32바이트여야 합니다.");
        }
        URI invitation = URI.create(environment.getRequiredProperty("app.workspace.invitation.accept-url"));
        if (!"https".equals(invitation.getScheme()) || invitation.getHost() == null
                || invitation.getHost().equals("localhost") || invitation.getHost().equals("127.0.0.1")) {
            throw new IllegalArgumentException("초대 URL은 공개 HTTPS 주소여야 합니다.");
        }
        // JWT_SECRET이 주입되지 않으면 git 이력에 공개된 개발 기본값으로 서명하게 된다.
        // issuer·audience도 커밋된 기본값이라 그 순간 access token은 누구나 위조할 수 있다.
        String jwtSecret = environment.getRequiredProperty("app.jwt.secret");
        if (COMMITTED_DEV_JWT_SECRET.equals(jwtSecret)
                || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT 서명 키는 커밋된 개발 기본값이 아닌 32바이트 이상이어야 합니다.");
        }
        // refresh 쿠키가 Secure 없이 나가면 14일짜리 refresh token이 평문 HTTP로 노출된다.
        if (!environment.getProperty("app.auth.refresh-cookie-secure", Boolean.class, false)) {
            throw new IllegalArgumentException("운영에서는 app.auth.refresh-cookie-secure가 true여야 합니다.");
        }
        int port = environment.getProperty("spring.mail.port", Integer.class, 0);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("필수 운영 설정 오류: spring.mail.port");
        }
        if (!environment.getProperty("app.auth.email-verification.dev-fixed-code", "").isBlank()) {
            throw new IllegalArgumentException("운영에서는 고정 이메일 인증 코드를 사용할 수 없습니다.");
        }
    }
}
