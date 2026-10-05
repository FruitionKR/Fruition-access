package fruition.access.user.service;

import fruition.access.user.exception.LoginRateLimitedException;
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
 * 비밀번호를 받는 로그인 경로의 시도 제한.
 *
 * <h2>실제로 막는 것</h2>
 * 한 출처에서 한 계정의 비밀번호를 반복해 찍는 것(single-account password spraying)과,
 * 한 출처에서 계정을 돌려가며 찍는 전체 시도량을 늦춘다.
 *
 * <h2>막지 못하는 것</h2>
 * credential stuffing은 막지 못한다. 계정 목록을 훑으며 계정마다 한 번씩 시도하는 공격이라
 * 계정별 실패 예산에 걸리지 않고, 프록시 풀을 쓰면 출구 노드마다 IP 예산 아래에 머문다.
 * 이것을 막으려면 유출 비밀번호 목록 대조나 기기·행동 신호가 필요하다. 이 장치는 그 대신
 * "한 출처가 한 계정을 두드리는" 좁은 경우를 늦추는 방어선이다.
 *
 * <h2>왜 두 축을 이렇게 묶었는가</h2>
 * 계정 축을 이메일만으로 묶으면 안 된다. 피해자 이메일을 아는 공격자가 아무 비밀번호로
 * 예산만큼 찍으면 피해자가 아예 로그인할 수 없게 된다 — 자격증명을 하나도 모르는 상태에서
 * 공짜로 계정을 잠그는 수단이고, 제한 장치가 오히려 공격 도구가 된다. 그래서 계정 축을
 * <b>(계정, 출처)</b> 쌍으로 묶는다. 공격자는 자기 출처의 예산만 태우고, 피해자가 자기
 * 출처에서 하는 로그인은 영향을 받지 않는다.
 *
 * <p>또 계정 축은 <b>실패만</b> 센다. 성공까지 세면 탭이나 기기를 여러 개 쓰는 정상
 * 사용자가 스스로 429를 맞는다. 성공하면 예산을 비워서, 오타 몇 번 뒤에 제대로 넣은
 * 사용자가 잠기지 않는다.
 *
 * <p>출처 주소는 {@code getRemoteAddr()}이 아니라 {@link fruition.shared.web.ClientAddressResolver}가
 * 신뢰 프록시 위치에서 뽑은 값이어야 한다. 맨 왼쪽 X-Forwarded-For 값은 공격자가 고르는
 * 문자열이라, 그 값으로 묶으면 위의 "남을 잠글 수 없다"는 성질이 그대로 깨진다.
 *
 * <h2>Redis 장애 때 동작: 열어 둔다</h2>
 * 의도적인 선택이다. 이 경로의 1차 방어선은 제한 장치가 아니라 비밀번호 검증(bcrypt)과
 * MFA다. 제한은 그 위에 얹은 보조 방어선이므로, 보조 장치가 죽었다고 전체 인증을 멈추면
 * rate limiter가 서비스 전체의 단일 장애점이 된다 — Redis가 내려가면 아무도 로그인할 수
 * 없다. 제한이 약해진 구간을 ERROR 로그로 드러내고 통과시키는 쪽이 기대 손실이 작다.
 *
 * <p>이메일 중복 확인(EmailAvailabilityRateLimiter)과 MFA(MfaAttemptLimiter)는 반대로 닫은
 * 채로 실패한다. 그쪽은 제한 장치가 유일한 방어선이라서다 — 열거를 막을 다른 수단이 없고,
 * 6자리 코드는 제한이 없으면 그냥 뚫린다. 비대칭은 의도된 것이다.
 */
@Service
public class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

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
    private final long ipLimit;
    private final long accountFailureLimit;

    public LoginAttemptLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.auth.login.window-seconds:300}") long windowSeconds,
            @Value("${app.auth.login.ip-limit:50}") long ipLimit,
            @Value("${app.auth.login.account-failure-limit:10}") long accountFailureLimit) {
        this.redisTemplate = redisTemplate;
        this.windowSeconds = windowSeconds;
        this.ipLimit = ipLimit;
        this.accountFailureLimit = accountFailureLimit;
    }

    /**
     * 비밀번호를 검증하기 <b>전에</b> 호출한다. 검증 뒤로 밀면 제한에 걸린 요청과 걸리지 않은
     * 요청의 응답 시간이 갈려 계정 존재 여부가 새어 나간다.
     */
    public void check(String email, String clientAddress) {
        long attempts = count(INCREMENT_WITH_TTL, volumeKey(clientAddress));
        if (attempts > ipLimit) {
            throw new LoginRateLimitedException(windowSeconds);
        }
        long failures = count(READ_COUNT, failureKey(email, clientAddress));
        if (failures >= accountFailureLimit) {
            throw new LoginRateLimitedException(windowSeconds);
        }
    }

    /** 비밀번호가 틀렸을 때만 호출한다. */
    public void recordFailure(String email, String clientAddress) {
        count(INCREMENT_WITH_TTL, failureKey(email, clientAddress));
    }

    /** 비밀번호가 맞았을 때 호출해 실패 예산을 비운다. */
    public void recordSuccess(String email, String clientAddress) {
        try {
            redisTemplate.delete(failureKey(email, clientAddress));
        } catch (DataAccessException e) {
            // 예산이 안 비워지면 다음 window까지 조금 좁게 남을 뿐이라 요청을 깨지 않는다.
            log.warn("[로그인 실패 예산 초기화 실패] Redis 접근 오류", e);
        }
    }

    private String volumeKey(String clientAddress) {
        return "auth:login:ip:" + sha256(clientAddress);
    }

    private String failureKey(String email, String clientAddress) {
        return "auth:login:fail:" + sha256(email.trim().toLowerCase()) + ":" + sha256(clientAddress);
    }

    /**
     * Redis를 못 쓰면 0으로 보고 통과시킨다. Lettuce는 장애를 null이 아니라
     * RedisConnectionFailureException 등으로 알리고, RedisTemplate이 그것을
     * DataAccessException으로 변환한다 — 둘 다 받는다. 왜 열어 두는지는 클래스 주석에 있다.
     */
    private long count(DefaultRedisScript<Long> script, String key) {
        try {
            Long value = redisTemplate.execute(script, List.of(key), String.valueOf(windowSeconds));
            if (value == null) {
                log.error("[로그인 시도 제한 미집계] Redis가 값을 주지 않아 제한 없이 통과시킨다. key={}", key);
                return 0;
            }
            return value;
        } catch (DataAccessException e) {
            log.error("[로그인 시도 제한 미집계] Redis 접근 오류로 제한 없이 통과시킨다. key={}", key, e);
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
