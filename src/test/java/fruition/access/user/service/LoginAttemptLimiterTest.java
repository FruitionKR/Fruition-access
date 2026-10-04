package fruition.access.user.service;

import fruition.access.user.exception.LoginRateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;

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

        assertThatCode(() -> limiter.check("test@example.com", "127.0.0.1")).doesNotThrowAnyException();
    }

    /** 같은 출처에서 여러 계정을 돌려가며 찍는 경우를 IP 예산으로 막는다. */
    @Test
    void check_ipLimitExceeded_throwsRateLimited() {
        doReturn(51L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> limiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    /** 분산된 출처에서 한 계정을 노리는 경우를 계정 예산으로 막는다. */
    @Test
    void check_emailLimitExceeded_throwsRateLimited() {
        doReturn(1L, 11L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> limiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    /** 제한을 세지 못했으면 비밀번호 대입을 열어주지 않고 닫힌 채로 실패한다. */
    @Test
    void check_redisReturnsNull_failsClosed() {
        doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> limiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
    }
}
