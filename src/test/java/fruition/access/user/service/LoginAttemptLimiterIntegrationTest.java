package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.exception.LoginRateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로그인 시도 제한이 제3자를 막지 않는지를 실제 Redis로 확인한다.
 *
 * <p>이전 구조는 제출된 이메일만으로 모든 시도를 셌다. 피해자 이메일을 아는 공격자가 아무
 * 비밀번호로 11번 찍으면 피해자가 아예 로그인할 수 없었다 — 자격증명을 하나도 모르는
 * 상태에서 공짜로 계정을 잠그는 수단이었다. 성공도 함께 세서 탭 여러 개를 띄운 정상
 * 사용자가 스스로 429를 맞기도 했다.
 *
 * <p>mock이 아니라 실제 Redis로 돌린다. 키 구성을 눈으로 확인하는 테스트는 "공격자가
 * 피해자를 잠그지 못한다"는 성질 자체를 증명하지 못한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LoginAttemptLimiterIntegrationTest {

    private static final long WINDOW = 300;
    private static final long IP_LIMIT = 5;
    private static final long FAILURE_LIMIT = 3;

    @Autowired StringRedisTemplate redisTemplate;

    private LoginAttemptLimiter limiter() {
        return new LoginAttemptLimiter(redisTemplate, WINDOW, IP_LIMIT, FAILURE_LIMIT);
    }

    private String email() {
        return "victim-" + UUID.randomUUID() + "@example.com";
    }

    private String ip() {
        return "203.0.113." + (int) (Math.random() * 250 + 1) + "-" + UUID.randomUUID();
    }

    /**
     * 핵심 요건. 공격자가 피해자 이메일로 실패를 쌓아도 피해자 자신의 출처에서는 로그인이
     * 막히지 않는다. 실패 예산이 (계정, 출처) 쌍으로 묶여 있어서, 공격자는 자기 예산만 태운다.
     */
    @Test
    void attackerFailuresDoNotLockTheVictimOut() {
        LoginAttemptLimiter limiter = limiter();
        String victimEmail = email();
        String attackerIp = ip();
        String victimIp = ip();

        for (int i = 0; i < FAILURE_LIMIT * 3; i++) {
            limiter.recordFailure(victimEmail, attackerIp);
        }

        // 공격자 출처에서는 막힌다.
        assertThatThrownBy(() -> limiter.check(victimEmail, attackerIp))
                .isInstanceOf(LoginRateLimitedException.class);
        // 피해자 출처에서는 그대로 열려 있다.
        assertThatCode(() -> limiter.check(victimEmail, victimIp)).doesNotThrowAnyException();
    }

    /** 한 출처에서 한 계정 비밀번호를 찍는 것은 막는다 — 이 장치가 실제로 사주는 것. */
    @Test
    void repeatedFailuresFromOneSourceBlockThatSource() {
        LoginAttemptLimiter limiter = limiter();
        String targetEmail = email();
        String sourceIp = ip();

        for (int i = 0; i < FAILURE_LIMIT; i++) {
            assertThatCode(() -> limiter.check(targetEmail, sourceIp)).doesNotThrowAnyException();
            limiter.recordFailure(targetEmail, sourceIp);
        }

        assertThatThrownBy(() -> limiter.check(targetEmail, sourceIp))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    /** 성공하면 실패 예산을 비운다 — 오타 몇 번 뒤에 제대로 넣은 사용자를 잠그지 않는다. */
    @Test
    void successfulLoginResetsTheFailureBudget() {
        LoginAttemptLimiter limiter = limiter();
        String userEmail = email();
        String userIp = ip();

        for (int i = 0; i < FAILURE_LIMIT; i++) {
            limiter.recordFailure(userEmail, userIp);
        }
        limiter.recordSuccess(userEmail, userIp);

        assertThatCode(() -> limiter.check(userEmail, userIp)).doesNotThrowAnyException();
    }

    /** 성공은 세지 않는다 — 탭·기기를 여러 개 쓰는 사용자가 스스로 429를 맞지 않는다. */
    @Test
    void successfulLoginsAreNotCountedAgainstTheAccount() {
        LoginAttemptLimiter limiter = limiter();
        String userEmail = email();

        for (int i = 0; i < FAILURE_LIMIT * 5; i++) {
            String freshIp = ip();
            limiter.check(userEmail, freshIp);
            limiter.recordSuccess(userEmail, freshIp);
        }

        assertThatCode(() -> limiter.check(userEmail, ip())).doesNotThrowAnyException();
    }

    /** 한 출처의 전체 시도량에는 상한이 있다 — 계정을 돌려가며 찍는 것을 늦춘다. */
    @Test
    void oneSourceCannotExceedItsVolumeBudget() {
        LoginAttemptLimiter limiter = limiter();
        String sourceIp = ip();

        for (int i = 0; i < IP_LIMIT; i++) {
            limiter.check(email(), sourceIp);
        }

        assertThatThrownBy(() -> limiter.check(email(), sourceIp))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    /** IP 예산은 출처마다 독립이다 — 남의 예산을 태울 수 없다. */
    @Test
    void volumeBudgetIsIndependentPerSource() {
        LoginAttemptLimiter limiter = limiter();
        String attackerIp = ip();
        String victimIp = ip();

        for (int i = 0; i < IP_LIMIT * 2; i++) {
            try {
                limiter.check(email(), attackerIp);
            } catch (LoginRateLimitedException expected) {
                // 공격자 예산은 소진된다.
            }
        }

        assertThatCode(() -> limiter.check(email(), victimIp)).doesNotThrowAnyException();
    }
}
