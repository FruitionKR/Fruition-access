package fruition.access.user.service;

import fruition.TestcontainersConfiguration;
import fruition.access.user.mail.EmailChangeNotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 이메일 변경 알림 outbox. 변경 트랜잭션과 함께 커밋·롤백되는지, 발송 작업이 성공·실패·최대 시도를
 * 실제 Postgres에서 어떻게 기록하는지 확인한다. 스케줄 실행은 테스트 프로필에서 미뤄 두고 {@code run(now)}를 직접 부른다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EmailChangeNotificationOutboxIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired EmailChangeNotificationJob job;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EmailChangeNotificationSender sender;

    final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM email_change_notifications");
    }

    @Test
    void committedChange_leavesMaskedNotificationInOutbox() {
        String userId = userId();

        transactionTemplate.executeWithoutResult(status -> publish(userId));

        Map<String, Object> row = rowOf(userId);
        assertThat(row.get("recipient_email")).isEqualTo(userId + "@example.com");
        assertThat(row.get("masked_new_email")).isEqualTo("ne*****@example.com");
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempts")).isEqualTo(0);
        verify(sender, never()).send(anyString(), anyString(), any());
    }

    @Test
    void rolledBackChange_leavesNoNotification() {
        String userId = userId();

        transactionTemplate.executeWithoutResult(status -> {
            publish(userId);
            status.setRollbackOnly();
        });

        assertThat(countOf(userId)).isZero();
    }

    @Test
    void eventOutsideTransaction_isRejected() {
        assertThatThrownBy(() -> publish(userId())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void successfulSend_deletesNotification() {
        String userId = userId();
        long id = insert(userId, 0, now);

        job.run(now);

        verify(sender).send(userId + "@example.com", "ne*****@example.com", now);
        assertThat(exists(id)).isFalse();
    }

    @Test
    void failedSend_incrementsAttemptsAndPostponesNextAttempt() {
        String userId = userId();
        long id = insert(userId, 0, now);
        doThrow(new MailSendException("smtp down")).when(sender).send(anyString(), anyString(), any());

        job.run(now);

        Map<String, Object> row = rowOf(userId);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("smtp down");
        // 첫 실패는 첫 간격(1분) 뒤에 다시 시도한다. 그 전에는 다시 고르지 않는다.
        Instant nextAttemptAt = ((Timestamp) row.get("next_attempt_at")).toInstant();
        assertThat(nextAttemptAt).isAfterOrEqualTo(now.plus(Duration.ofMinutes(1)))
                .isBefore(now.plus(Duration.ofMinutes(2)));
        job.run(now.plusSeconds(30));
        verify(sender, times(1)).send(anyString(), anyString(), any());
        assertThat(exists(id)).isTrue();
    }

    @Test
    void retriesWithDoublingBackoff() {
        String userId = userId();
        insert(userId, 2, now);
        doThrow(new MailSendException("smtp down")).when(sender).send(anyString(), anyString(), any());

        job.run(now);

        // 세 번째 시도가 실패하면 1분 × 2^2 = 4분 뒤로 미룬다.
        Instant nextAttemptAt = ((Timestamp) rowOf(userId).get("next_attempt_at")).toInstant();
        assertThat(nextAttemptAt).isAfterOrEqualTo(now.plus(Duration.ofMinutes(4)))
                .isBefore(now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void lastAttemptFailure_marksNotificationFailed() {
        String userId = userId();
        insert(userId, 4, now);
        doThrow(new MailSendException("smtp down")).when(sender).send(anyString(), anyString(), any());

        job.run(now);

        Map<String, Object> row = rowOf(userId);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("attempts")).isEqualTo(5);
        job.run(now.plus(Duration.ofDays(1)));
        verify(sender, times(1)).send(anyString(), anyString(), any());
    }

    @Test
    void notificationBeingSent_isNotPickedByAnotherRun() {
        String userId = userId();
        insert(userId, 0, now);
        // 발송 도중 다른 replica가 같은 시각에 작업을 돌려도 이미 집어 간 알림은 다시 고르지 않는다.
        doAnswer(invocation -> {
            job.run(now);
            return null;
        }).when(sender).send(anyString(), anyString(), any());

        job.run(now);

        verify(sender, times(1)).send(anyString(), anyString(), any());
        assertThat(countOf(userId)).isZero();
    }

    /** 발송은 됐는데 행을 지우지 못하면 발송 실패로 세지 않는다. 마지막 시도였어도 failed로 남기지 않는다. */
    @Test
    void deleteFailureAfterSend_isNotRecordedAsSendFailure() {
        String userId = userId();
        long id = insert(userId, 4, now);
        JdbcTemplate failingDelete = spy(jdbc);
        doThrow(new org.springframework.dao.QueryTimeoutException("db down"))
                .when(failingDelete).update(eq(DELETE_SQL), eq(id));

        jobWith(failingDelete, Clock.systemUTC()).run(now);

        verify(sender, times(1)).send(anyString(), anyString(), any());
        // 일시적인 오류일 수 있어 한 번 더 지워 본다.
        verify(failingDelete, times(2)).update(DELETE_SQL, id);
        Map<String, Object> row = rowOf(userId);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("last_error")).isNull();
    }

    @Test
    void deleteFailingOnce_isRetriedAndDeletes() {
        String userId = userId();
        long id = insert(userId, 0, now);
        JdbcTemplate flakyDelete = spy(jdbc);
        doThrow(new org.springframework.dao.QueryTimeoutException("db down"))
                .doCallRealMethod()
                .when(flakyDelete).update(eq(DELETE_SQL), eq(id));

        jobWith(flakyDelete, Clock.systemUTC()).run(now);

        assertThat(exists(id)).isFalse();
    }

    /** 배치가 대기 시간에 가까워지면 남은 행은 보내지 않고 시도 횟수를 되돌려 바로 다시 고를 수 있게 둔다. */
    @Test
    void batchNearingLease_releasesUnsentNotifications() {
        String userId = userId();
        insert(userId, 0, now);
        insert(userId, 0, now);
        insert(userId, 0, now);
        MutableClock clock = new MutableClock(now);
        // 첫 발송이 SMTP 지연으로 마감 시간을 다 쓴 것으로 둔다.
        doAnswer(invocation -> {
            clock.advance(EmailChangeNotificationJob.SEND_DEADLINE);
            return null;
        }).when(sender).send(anyString(), anyString(), any());

        jobWith(jdbc, clock).run(now);

        verify(sender, times(1)).send(anyString(), anyString(), any());
        assertThat(countOf(userId)).isEqualTo(2);
        Instant releasedAt = now.plus(EmailChangeNotificationJob.SEND_DEADLINE);
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM email_change_notifications WHERE user_id = ?", userId)) {
            assertThat(row.get("attempts")).isEqualTo(0);
            assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isEqualTo(releasedAt);
        }

        // 다음 실행이 남은 행을 바로 보낸다.
        jobWith(jdbc, Clock.systemUTC()).run(releasedAt);
        verify(sender, times(3)).send(anyString(), anyString(), any());
        assertThat(countOf(userId)).isZero();
    }

    private static final String DELETE_SQL = "DELETE FROM email_change_notifications WHERE id = ?";

    private EmailChangeNotificationJob jobWith(JdbcTemplate jdbcTemplate, Clock clock) {
        return new EmailChangeNotificationJob(jdbcTemplate, sender, 5, Duration.ofMinutes(1), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private void publish(String userId) {
        eventPublisher.publishEvent(new EmailChangedEvent(userId, userId + "@example.com", "newuser@example.com", now));
    }

    private long insert(String userId, int attempts, Instant nextAttemptAt) {
        return jdbc.queryForObject("""
                INSERT INTO email_change_notifications(user_id, recipient_email, masked_new_email, changed_at,
                    attempts, next_attempt_at)
                VALUES (?, ?, 'ne*****@example.com', ?, ?, ?) RETURNING id
                """, Long.class, userId, userId + "@example.com", Timestamp.from(now), attempts,
                Timestamp.from(nextAttemptAt));
    }

    private Map<String, Object> rowOf(String userId) {
        return jdbc.queryForMap("SELECT * FROM email_change_notifications WHERE user_id = ?", userId);
    }

    private int countOf(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM email_change_notifications WHERE user_id = ?",
                Integer.class, userId);
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM email_change_notifications WHERE id = ?",
                Integer.class, id) == 1;
    }

    private static String userId() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
