package fruition.access.user.service;

import fruition.access.user.exception.PasswordChangeRateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 비밀번호 변경의 "현재 비밀번호" 확인에 걸는 실패 예산.
 *
 * <p>이 확인에 제한이 없으면 access token을 훔친 공격자가 현재 비밀번호를 무제한으로 대입할
 * 수 있다. 로그인 경로에만 제한을 걸어 두면 같은 비밀번호 검증이 제한 없는 문으로 그대로
 * 열려 있는 셈이다. 알아낸 비밀번호는 보통 다른 서비스에서도 통하므로, 토큰 하나의 탈취가
 * 비밀번호 유출로 번진다.
 *
 * <p>예산은 (사용자, 출처) 쌍으로 묶는다. 공격자는 자기 출처의 예산만 태우고, 정상
 * 사용자는 자기 기기에서 비밀번호를 계속 바꿀 수 있다. 막히는 범위도 비밀번호 변경
 * 한 기능이고 로그인은 그대로 된다.
 *
 * <p>Redis 장애 때 열어 두는 판단은 {@link LoginAttemptLimiter}와 같다 — 1차 방어선은
 * 비밀번호 검증이고, 보조 장치가 인증 기능 전체를 멈추게 두지 않는다.
 */
@Service
public class PasswordChangeAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeAttemptLimiter.class);

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> READ_COUNT = new DefaultRedisScript<>("""
            return tonumber(redis.call('GET', KEYS[1]) or '0')
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long windowSeconds;
    private final long failureLimit;

    public PasswordChangeAttemptLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.password-change.window-seconds:300}") long windowSeconds,
            @Value("${app.auth.password-change.failure-limit:5}") long failureLimit) {
        this.redisTemplate = redisTemplate;
        this.windowSeconds = windowSeconds;
        this.failureLimit = failureLimit;
    }

    /** 현재 비밀번호를 확인하기 전에 호출한다. */
    public void check(String userId, String clientAddress) {
        if (count(READ_COUNT, key(userId, clientAddress)) >= failureLimit) {
            throw new PasswordChangeRateLimitedException(windowSeconds);
        }
    }

    /** 현재 비밀번호가 틀렸을 때만 호출한다. */
    public void recordFailure(String userId, String clientAddress) {
        count(INCREMENT_WITH_TTL, key(userId, clientAddress));
    }

    /** 맞았을 때 호출해 예산을 비운다. */
    public void recordSuccess(String userId, String clientAddress) {
        try {
            redisTemplate.delete(key(userId, clientAddress));
        } catch (DataAccessException e) {
            log.warn("[비밀번호 변경 실패 예산 초기화 실패] Redis 접근 오류", e);
        }
    }

    private String key(String userId, String clientAddress) {
        return "auth:password-change:fail:" + sha256(userId) + ":" + sha256(clientAddress);
    }

    private long count(DefaultRedisScript<Long> script, String key) {
        try {
            Long value = redisTemplate.execute(script, List.of(key), String.valueOf(windowSeconds));
            if (value == null) {
                log.error("[비밀번호 변경 제한 미집계] Redis가 값을 주지 않아 제한 없이 통과시킨다. key={}", key);
                return 0;
            }
            return value;
        } catch (DataAccessException e) {
            log.error("[비밀번호 변경 제한 미집계] Redis 접근 오류로 제한 없이 통과시킨다. key={}", key, e);
            return 0;
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
