package fruition.access.user.service;

import fruition.access.user.exception.LoginRateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 비밀번호를 받는 로그인 경로의 fixed-window 제한.
 *
 * <p>IP와 계정 두 축으로 센다. IP 예산만 두면 분산된 출처에서 한 계정을 노리는 대입을
 * 막지 못하고, 계정 예산만 두면 같은 출처가 계정을 돌려가며 찍는 것을 막지 못한다.
 * MfaAttemptLimiter는 사용자별이라 MFA 사용 계정의 비밀번호 추측까지 늦추지 못한다.
 */
@Service
public class LoginAttemptLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long windowSeconds;
    private final long ipLimit;
    private final long emailLimit;

    public LoginAttemptLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.login.window-seconds:300}") long windowSeconds,
            @Value("${app.auth.login.ip-limit:50}") long ipLimit,
            @Value("${app.auth.login.email-limit:10}") long emailLimit) {
        this.redisTemplate = redisTemplate;
        this.windowSeconds = windowSeconds;
        this.ipLimit = ipLimit;
        this.emailLimit = emailLimit;
    }

    public void check(String email, String clientAddress) {
        enforce("ip", clientAddress, ipLimit);
        enforce("email", email.trim().toLowerCase(), emailLimit);
    }

    private void enforce(String scope, String value, long limit) {
        String key = "auth:login:" + scope + ":" + sha256(value);
        Long count = redisTemplate.execute(
                INCREMENT_WITH_TTL,
                List.of(key),
                String.valueOf(windowSeconds));
        // null이면 제한을 세지 못한 것이다. 통과시키면 Redis 장애 동안 비밀번호 대입이
        // 무제한으로 열리므로 닫힌 채로 실패한다.
        if (count == null) {
            throw new IllegalStateException("로그인 시도 제한을 확인하지 못했습니다.");
        }
        if (count > limit) {
            throw new LoginRateLimitedException(windowSeconds);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}
