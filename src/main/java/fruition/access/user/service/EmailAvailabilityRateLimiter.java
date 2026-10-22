package fruition.access.user.service;

import fruition.access.user.exception.EmailAvailabilityRateLimitedException;
import fruition.access.user.exception.VerificationRateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongFunction;

/**
 * 이메일 존재 여부를 드러낼 수 있는 비인증 요청의 IP별·이메일별 제한.
 *
 * <p>{@code /email-availability}와 {@code /email-verifications}는 같은 창·한도를 쓰되 버킷을 나눈다.
 * 가입 화면은 중복 확인 뒤 인증번호를 요청하므로, 버킷을 공유하면 중복 확인 몇 번에 정상 인증번호 요청이 막힌다.
 */
@Service
public class EmailAvailabilityRateLimiter {

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

    public EmailAvailabilityRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.email-availability.window-seconds:60}") long windowSeconds,
            @Value("${app.auth.email-availability.ip-limit:30}") long ipLimit,
            @Value("${app.auth.email-availability.email-limit:5}") long emailLimit) {
        this.redisTemplate = redisTemplate;
        this.windowSeconds = windowSeconds;
        this.ipLimit = ipLimit;
        this.emailLimit = emailLimit;
    }

    public void check(String email, String clientAddress) {
        check("auth:email-availability:", email, clientAddress, EmailAvailabilityRateLimitedException::new);
    }

    /**
     * 인증번호 발급 요청 제한. 발급 서비스의 쿨다운·일일 한도는 발급된 레코드로 세는데, 거절된 요청
     * (signup·email_change의 409)은 롤백돼 레코드가 남지 않으므로 그 한도에 걸리지 않는다.
     * 거절 응답으로 존재 여부를 반복 조회하지 못하게 여기서 요청 자체를 센다. 초과하면 이 API의 기존 429와 같은
     * {@code VERIFICATION_RATE_LIMITED}로 응답한다.
     */
    public void checkVerificationRequest(String email, String clientAddress) {
        check("auth:email-verification-request:", email, clientAddress, VerificationRateLimitedException::new);
    }

    private void check(String keyPrefix, String email, String clientAddress,
                       LongFunction<? extends RuntimeException> rateLimited) {
        enforce(keyPrefix + "ip:", clientAddress, ipLimit, rateLimited);
        enforce(keyPrefix + "email:", email.trim().toLowerCase(), emailLimit, rateLimited);
    }

    private void enforce(String keyPrefix, String value, long limit, LongFunction<? extends RuntimeException> rateLimited) {
        String key = keyPrefix + sha256(value);
        Long count = redisTemplate.execute(
                INCREMENT_WITH_TTL,
                List.of(key),
                String.valueOf(windowSeconds));
        // null이면 제한을 세지 못한 것이다. 통과시키면 Redis 장애 동안 계정 열거가 무제한으로
        // 열리므로, MfaAttemptLimiter와 같이 닫힌 채로 실패한다.
        if (count == null) {
            throw new IllegalStateException("이메일 요청 제한을 확인하지 못했습니다.");
        }
        if (count > limit) {
            throw rateLimited.apply(windowSeconds);
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
