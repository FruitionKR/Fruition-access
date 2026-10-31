package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserRefreshToken;
import fruition.access.user.dto.LoginResponse;
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

/**
 * 같은 refresh token으로 동시에 회전하면 한 번만 성공한다. 세션 폐기·로그아웃이 회전과 겹쳐도
 * 회전이 만든 후속 토큰까지 폐기된다.
 */
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

    /** 회전이 먼저 커밋되면, 기다리던 세션 폐기는 방금 발급된 후속 토큰까지 폐기한다. */
    @Test
    void revokeSessionWaitingOnRefresh_revokesSuccessor() throws Exception {
        String id = user();
        String token = UUID.randomUUID().toString();
        Long sessionId = refreshTokens.saveAndFlush(
                new UserRefreshToken(id, OpaqueTokens.sha256(token), Instant.now().plusSeconds(3600))).getId();

        var executor = Executors.newSingleThreadExecutor();
        try {
            Pending pending = new TransactionTemplate(transactions).execute(status -> {
                LoginResponse rotated = auth.refresh(new RefreshRequest(token));
                Future<?> revoke = executor.submit(() -> auth.revokeSession(id, sessionId));
                awaitLock();
                return new Pending(rotated, revoke);
            });
            pending.waiting().get(10, TimeUnit.SECONDS);

            assertThat(refreshTokens.findAllByUserIdAndRevokedAtIsNull(id)).isEmpty();
            assertThatThrownBy(() -> auth.refresh(new RefreshRequest(pending.rotated().refreshToken())))
                    .isInstanceOf(InvalidRefreshTokenException.class);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 폐기가 먼저 커밋되면, 기다리던 회전은 폐기된 토큰을 보고 거절된다. */
    @Test
    void refreshWaitingOnRevokeSession_isRejected() throws Exception {
        String id = user();
        String token = UUID.randomUUID().toString();
        Long sessionId = refreshTokens.saveAndFlush(
                new UserRefreshToken(id, OpaqueTokens.sha256(token), Instant.now().plusSeconds(3600))).getId();

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> refresh = new TransactionTemplate(transactions).execute(status -> {
                auth.revokeSession(id, sessionId);
                Future<?> result = executor.submit(() -> auth.refresh(new RefreshRequest(token)));
                awaitLock();
                return result;
            });
            assertThatThrownBy(() -> refresh.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InvalidRefreshTokenException.class);
            assertThat(refreshTokens.findAllByUserIdAndRevokedAtIsNull(id)).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 회전 직전 쿠키로 로그아웃해도 같은 기기의 후속 토큰이 남지 않는다. */
    @Test
    void logoutWaitingOnRefresh_revokesSuccessor() throws Exception {
        String id = user();
        String token = UUID.randomUUID().toString();
        refreshTokens.saveAndFlush(new UserRefreshToken(id, OpaqueTokens.sha256(token), Instant.now().plusSeconds(3600)));

        var executor = Executors.newSingleThreadExecutor();
        try {
            Pending pending = new TransactionTemplate(transactions).execute(status -> {
                LoginResponse rotated = auth.refresh(new RefreshRequest(token));
                Future<?> logout = executor.submit(() -> auth.logout(new RefreshRequest(token)));
                awaitLock();
                return new Pending(rotated, logout);
            });
            pending.waiting().get(10, TimeUnit.SECONDS);

            assertThat(refreshTokens.findAllByUserIdAndRevokedAtIsNull(id)).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 정상 회전은 계속 되고 같은 계보를 잇는다. 다른 기기 세션은 폐기에 휘말리지 않는다. */
    @Test
    void sequentialRefresh_keepsFamily_andRevokeSessionLeavesOtherDevices() {
        String id = user();
        String first = UUID.randomUUID().toString();
        String otherDevice = UUID.randomUUID().toString();
        UserRefreshToken root = refreshTokens.saveAndFlush(
                new UserRefreshToken(id, OpaqueTokens.sha256(first), Instant.now().plusSeconds(3600)));
        refreshTokens.saveAndFlush(new UserRefreshToken(id, OpaqueTokens.sha256(otherDevice), Instant.now().plusSeconds(3600)));

        String second = auth.refresh(new RefreshRequest(first)).refreshToken();
        String third = auth.refresh(new RefreshRequest(second)).refreshToken();
        assertThat(refreshTokens.findAllByFamilyIdAndRevokedAtIsNull(root.getFamilyId()))
                .extracting(UserRefreshToken::getTokenHash)
                .containsExactly(OpaqueTokens.sha256(third));

        // 오래된 세션 목록의 id로 폐기해도 지금 살아 있는 후속 토큰이 끊긴다.
        auth.revokeSession(id, root.getId());

        assertThatThrownBy(() -> auth.refresh(new RefreshRequest(third)))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(auth.refresh(new RefreshRequest(otherDevice)).refreshToken()).isNotBlank();
    }

    /**
     * V30 이전 행이나 배포 중 이전 버전 인스턴스가 family_id 없이 넣은 행도 DB 기본값으로 계보를 갖는다.
     * 행마다 다른 계보이고, 그 행에서 시작한 회전·폐기도 정상 동작한다.
     */
    @Test
    void legacyRowWithoutFamily_getsOwnFamily_andRefreshRevokeStillWork() {
        String id = user();
        String legacy = UUID.randomUUID().toString();
        String otherLegacy = UUID.randomUUID().toString();
        for (String token : new String[]{legacy, otherLegacy}) {
            jdbc.update("INSERT INTO user_refresh_tokens(created_at, expires_at, token_hash, user_id) VALUES (now(), ?, ?, ?)",
                    java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), OpaqueTokens.sha256(token), id);
        }
        Long legacyId = jdbc.queryForObject("SELECT id FROM user_refresh_tokens WHERE token_hash = ?",
                Long.class, OpaqueTokens.sha256(legacy));
        String legacyFamily = jdbc.queryForObject("SELECT family_id FROM user_refresh_tokens WHERE id = ?",
                String.class, legacyId);
        String otherFamily = jdbc.queryForObject("SELECT family_id FROM user_refresh_tokens WHERE token_hash = ?",
                String.class, OpaqueTokens.sha256(otherLegacy));
        assertThat(legacyFamily).isNotBlank().isNotEqualTo(otherFamily);

        String rotated = auth.refresh(new RefreshRequest(legacy)).refreshToken();
        assertThat(jdbc.queryForObject("SELECT family_id FROM user_refresh_tokens WHERE token_hash = ?",
                String.class, OpaqueTokens.sha256(rotated))).isEqualTo(legacyFamily);

        auth.revokeSession(id, legacyId);

        assertThatThrownBy(() -> auth.refresh(new RefreshRequest(rotated)))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(auth.refresh(new RefreshRequest(otherLegacy)).refreshToken()).isNotBlank();
    }

    private String user() {
        String id = UUID.randomUUID().toString();
        users.saveAndFlush(new User(id, id + "@example.com", "local", "사용자", null));
        return id;
    }

    private record Pending(LoginResponse rotated, Future<?> waiting) {}

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
