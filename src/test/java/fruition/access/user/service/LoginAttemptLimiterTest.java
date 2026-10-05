package fruition.access.user.service;

import fruition.access.user.exception.LoginRateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * 키 구성과 제3자 영향 없음은 실제 Redis를 쓰는 LoginAttemptLimiterIntegrationTest가 증명한다.
 * 여기서는 Redis가 응답하지 않을 때의 동작만 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptLimiterTest {

    @Mock StringRedisTemplate redisTemplate;

    LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginAttemptLimiter(redisTemplate, 300, 50, 10);
    }

    @Test
    void check_withinLimits_passes() {
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatCode(() -> limiter.check("test@example.com", "203.0.113.9")).doesNotThrowAnyException();
    }

    /** 한 출처의 전체 시도량 상한. */
    @Test
    void check_ipVolumeExceeded_throwsRateLimited() {
        doReturn(51L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> limiter.check("test@example.com", "203.0.113.9"))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    /** (계정, 출처) 쌍의 실패 예산이 차면 그 출처만 막힌다. */
    @Test
    void check_accountFailureBudgetExhausted_throwsRateLimited() {
        doReturn(1L, 10L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> limiter.check("test@example.com", "203.0.113.9"))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    /**
     * Redis 장애는 null이 아니라 예외로 온다. 의도적으로 열어 둔다 — 이 경로의 1차 방어선은
     * 비밀번호 검증이고, 보조 장치 때문에 전체 인증이 멈추면 rate limiter가 단일 장애점이 된다.
     */
    @Test
    void check_redisUnavailable_failsOpenSoLoginStaysUp() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatCode(() -> limiter.check("test@example.com", "203.0.113.9")).doesNotThrowAnyException();
    }

    /** null 응답도 같은 판단으로 통과시킨다. */
    @Test
    void check_redisReturnsNull_failsOpen() {
        doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatCode(() -> limiter.check("test@example.com", "203.0.113.9")).doesNotThrowAnyException();
    }

    /** 예산 초기화가 실패해도 로그인 응답을 깨지 않는다. */
    @Test
    void recordSuccess_redisUnavailable_doesNotBreakTheRequest() {
        doThrow(new RedisConnectionFailureException("redis down")).when(redisTemplate).delete(any(String.class));

        assertThatCode(() -> limiter.recordSuccess("test@example.com", "203.0.113.9"))
                .doesNotThrowAnyException();
    }
}
