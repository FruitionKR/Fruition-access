package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserRefreshToken;
import fruition.access.user.dto.RefreshRequest;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.repository.UserRefreshTokenRepository;
import fruition.access.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 같은 refresh token으로 동시에 회전하면 한 번만 성공한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RefreshTokenConcurrencyIntegrationTest {
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired UserRefreshTokenRepository refreshTokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentRefresh_sameToken_onlyOneSucceeds() throws Exception {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", null));
        String token = UUID.randomUUID().toString();
        refreshTokens.saveAndFlush(new UserRefreshToken(id, OpaqueTokens.sha256(token), Instant.now().plusSeconds(3600)));

        var executor = Executors.newSingleThreadExecutor();
        try {
            // 첫 회전이 커밋되기 전에 두 번째 회전이 같은 행의 잠금을 기다리게 한다.
            Future<?> second = new TransactionTemplate(transactions).execute(status -> {
                auth.refresh(new RefreshRequest(token));
                Future<?> result = executor.submit(() -> auth.refresh(new RefreshRequest(token)));
                awaitLock();
                return result;
            });
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidRefreshTokenException.class);
            assertThat(refreshTokens.findAllByUserIdAndRevokedAtIsNull(id)).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock' AND query LIKE '%user_refresh_tokens%')
                    """, Boolean.class))) return;
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("두 번째 refresh 요청이 DB 잠금 대기에 도달하지 않았습니다.");
    }
}
