package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.dto.EmailVerificationRequest;
import fruition.access.user.dto.VerificationConfirmRequest;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.VerificationRateLimitedException;
import fruition.access.user.repository.EmailVerificationRepository;
import fruition.access.user.mail.EmailVerificationSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 인증번호 발급(재발급·동시 요청)이 실제 DB에서 이전 토큰과 rate limit을 올바르게 다루는지 확인한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailVerificationIssueIntegrationTest {

    static final String PURPOSE = "password_reset";

    @Autowired EmailVerificationService verificationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailVerificationRepository verifications;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean EmailVerificationSender sender;

    @Test
    void reissue_invalidatesTokenConfirmedFromPreviousCode() {
        String email = uniqueEmail();
        String firstId = verificationService.request(new EmailVerificationRequest(email, PURPOSE)).verificationId();
        String firstToken = verificationService.confirm(firstId, new VerificationConfirmRequest(lastCode(email)))
                .verificationToken();
        passCooldown(email);

        String secondId = verificationService.request(new EmailVerificationRequest(email, PURPOSE)).verificationId();

        assertThatThrownBy(() -> verificationService.consumeForPasswordReset(email, firstToken))
                .isInstanceOf(InvalidVerificationTokenException.class);
        String secondToken = verificationService.confirm(secondId, new VerificationConfirmRequest(lastCode(email)))
                .verificationToken();
        assertThatCode(() -> verificationService.consumeForPasswordReset(email, secondToken)).doesNotThrowAnyException();
    }

    @Test
    void concurrentRequests_onlyOnePassesCooldown() throws Exception {
        String email = uniqueEmail();
        var executor = Executors.newFixedThreadPool(2);
        try {
            // 발급 잠금을 먼저 잡아 두 요청을 같은 출발선에 세우고, 둘 다 DB 잠금에서 기다리는지 확인한 뒤 놓는다.
            List<Future<?>> requests = new TransactionTemplate(transactionManager).execute(status -> {
                verifications.lockIssuance(PURPOSE + ":" + email);
                List<Future<?>> submitted = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    submitted.add(executor.submit(() ->
                            verificationService.request(new EmailVerificationRequest(email, PURPOSE))));
                }
                awaitAdvisoryLockWaiters(2);
                return submitted;
            });

            int succeeded = 0;
            int rateLimited = 0;
            for (Future<?> request : requests) {
                try {
                    request.get(10, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException exception) {
                    assertThat(exception).hasCauseInstanceOf(VerificationRateLimitedException.class);
                    rateLimited++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
            assertThat(rateLimited).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM email_verifications WHERE email = ?",
                    Long.class, email)).isEqualTo(1L);
            verify(sender, times(1)).send(eq(email), eq(PURPOSE), anyString());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitAdvisoryLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            // 트랜잭션 안의 통계 스냅샷을 갱신해 다른 연결의 현재 대기를 조회한다.
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock' AND wait_event = 'advisory'
                    """, Long.class);
            if (waiting != null && waiting >= expected) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("동시 발급 요청이 발급 잠금 대기에 도달하지 않았습니다.");
    }

    private String lastCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).send(eq(email), eq(PURPOSE), code.capture());
        List<String> codes = code.getAllValues();
        return codes.get(codes.size() - 1);
    }

    /** 재발급 쿨다운을 지난 것으로 만든다. */
    private void passCooldown(String email) {
        jdbc.update("UPDATE email_verifications SET created_at = created_at - interval '10 minutes' WHERE email = ?",
                email);
    }

    private static String uniqueEmail() {
        return "issue_" + UUID.randomUUID().toString().replace("-", "") + "@example.com";
    }
}
