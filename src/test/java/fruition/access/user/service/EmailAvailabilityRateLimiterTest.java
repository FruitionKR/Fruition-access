package fruition.access.user.service;

import fruition.access.user.exception.EmailAvailabilityRateLimitedException;
import fruition.access.user.exception.VerificationRateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailAvailabilityRateLimiterTest {

    @Mock StringRedisTemplate redisTemplate;

    EmailAvailabilityRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new EmailAvailabilityRateLimiter(redisTemplate, 60, 30, 5);
    }

    @Test
    void check_ipLimitExceeded_throwsRateLimited() {
        doReturn(31L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> rateLimiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(EmailAvailabilityRateLimitedException.class);
    }

    @Test
    void check_emailLimitExceeded_throwsRateLimited() {
        doReturn(1L, 6L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> rateLimiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(EmailAvailabilityRateLimitedException.class);
    }

    /**
     * script 결과가 null이면 제한을 확인하지 못한 것이다. 통과시키면 계정 열거 방어선이
     * Redis 장애와 동시에 조용히 사라진다 — MfaAttemptLimiter처럼 닫힌 채로 실패해야 한다.
     */
    @Test
    void check_redisReturnsNull_failsClosed() {
        doReturn(null).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> rateLimiter.check("test@example.com", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
    }
    /** 인증번호 요청은 이 API의 기존 429와 같은 VERIFICATION_RATE_LIMITED(Retry-After=창 길이)로 막는다. */
    @Test
    void checkVerificationRequest_emailLimitExceeded_throwsVerificationRateLimited() {
        doReturn(1L, 6L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(String.class));

        assertThatThrownBy(() -> rateLimiter.checkVerificationRequest("test@example.com", "127.0.0.1"))
                .isInstanceOfSatisfying(VerificationRateLimitedException.class,
                        e -> assertThat(e.getRetryAfter()).isEqualTo(60));
    }

    /** 가입 화면의 중복 확인이 인증번호 요청 한도를 쓰지 않게 두 API는 다른 키로 센다. */
    @SuppressWarnings("unchecked")
    @Test
    void checkVerificationRequest_usesBucketsSeparateFromAvailability() {
        doReturn(1L).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));

        rateLimiter.check("test@example.com", "127.0.0.1");
        rateLimiter.checkVerificationRequest("test@example.com", "127.0.0.1");

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(4)).execute(any(RedisScript.class), keys.capture(), eq("60"));
        List<String> all = keys.getAllValues().stream().map(k -> k.get(0)).toList();
        assertThat(all).doesNotHaveDuplicates();
        assertThat(all.subList(0, 2)).allMatch(k -> k.startsWith("auth:email-availability:"));
        assertThat(all.subList(2, 4)).allMatch(k -> k.startsWith("auth:email-verification-request:"));
    }
}
