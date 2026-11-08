package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.security.OpaqueTokens;
import fruition.access.user.domain.EmailVerification;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.exception.InvalidVerificationCodeException;
import fruition.access.user.exception.VerificationCodeAttemptsExceededException;
import fruition.access.user.exception.VerificationRateLimitedException;
import fruition.access.user.repository.EmailVerificationRepository;
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
 * 인증번호 확인과 발급이 실제 PostgreSQL 잠금으로 직렬화되는지 본다.
 * 첫 요청의 트랜잭션을 열어 둔 채 두 번째 요청이 DB 잠금 대기에 들어간 것을 확인하고 커밋한다.
 * 잠그지 않던 이전 구현에서는 두 번째 요청이 첫 요청의 결과를 보지 못해 한도를 함께 통과했다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailVerificationConcurrencyIntegrationTest {
    @Autowired EmailVerificationService service;
    @Autowired EmailVerificationRepository verifications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    /** max-attempts 기본값(5)보다 하나 적게 틀린 상태에서 동시 오답 두 개는 한 번만 비교된다. */
    @Test
    void concurrentWrongCodes_atLastAttempt_secondIsBlocked() throws Exception {
        String id = "ev_" + UUID.randomUUID().toString().replace("-", "");
        EmailVerification verification = new EmailVerification(id, id + "@example.com", "password_reset",
                OpaqueTokens.sha256("135790"), Instant.now().plusSeconds(300));
        for (int i = 0; i < 4; i++) {
            verification.increaseAttempt();
        }
        verifications.saveAndFlush(verification);

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> second = new TransactionTemplate(transactions).execute(status -> {
                assertThatThrownBy(() -> service.confirm(id, new VerificationConfirmRequest("000000")))
                        .isInstanceOf(InvalidVerificationCodeException.class);
                Future<?> result = executor.submit(() -> service.confirm(id, new VerificationConfirmRequest("000001")));
                awaitLock("email_verifications");
                return result;
            });
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(VerificationCodeAttemptsExceededException.class);
            assertThat(verifications.findById(id).orElseThrow().getAttemptCount()).isEqualTo(5);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 같은 수신자·용도의 동시 발급은 cooldown을 함께 통과하지 못한다. */
    @Test
    void concurrentRequests_sameEmailAndPurpose_onlyOneIssued() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var request = new EmailVerificationRequest(email, "password_reset");

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> second = new TransactionTemplate(transactions).execute(status -> {
                service.request(request);
                Future<?> result = executor.submit(() -> service.request(request));
                awaitLock("pg_advisory_xact_lock");
                return result;
            });
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(VerificationRateLimitedException.class);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM email_verifications WHERE email = ? AND purpose = 'password_reset'",
                    Long.class, email)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 다른 수신자의 발급은 서로 기다리지 않는다. */
    @Test
    void requests_differentEmails_doNotBlockEachOther() throws Exception {
        String first = UUID.randomUUID() + "@example.com";
        String other = UUID.randomUUID() + "@example.com";

        var executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.request(new EmailVerificationRequest(first, "password_reset"));
                Future<?> result = executor.submit(() -> service.request(new EmailVerificationRequest(other, "password_reset")));
                try {
                    result.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("다른 이메일 발급이 첫 트랜잭션을 기다렸습니다.", e);
                }
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitLock(String queryFragment) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                    AND wait_event_type = 'Lock' AND query LIKE ?)
                    """, Boolean.class, "%" + queryFragment + "%"))) return;
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
        throw new AssertionError("두 번째 요청이 DB 잠금 대기에 도달하지 않았습니다.");
    }
}
