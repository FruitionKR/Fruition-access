package fruition.access.user.service;

import fruition.access.user.mail.EmailChangeNotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 이메일 변경 알림 outbox({@code email_change_notifications})를 읽어 기존 이메일로 발송한다.
 *
 * <p>발송할 행은 {@code FOR UPDATE SKIP LOCKED}로 고르고, 같은 문장에서 시도 횟수를 올리며 다음 시도 시각을
 * 발송 대기 시간({@link #CLAIM_LEASE})만큼 미뤄 둔다. 이 문장이 커밋되면 잠금이 풀리지만 다른 replica는 그 시각
 * 전까지 행을 다시 고르지 않으므로 같은 알림을 두 번 보내지 않는다. SMTP 호출은 그 뒤 트랜잭션 밖에서 해
 * 느린 메일 서버가 DB 커넥션을 붙잡지 않는다. 발송 중 프로세스가 죽으면 대기 시간이 지난 뒤 다시 보낸다.
 *
 * <p>성공하면 행을 지운다(기존 이메일을 필요 이상 보관하지 않는다). 실패하면 첫 간격에서 시작해 두 배씩 늘려
 * 다시 시도하고, 최대 시도에 닿으면 {@code failed}로 남기고 error 로그를 남긴다. 발송은 됐는데 행을 지우지 못하면
 * 발송 실패로 세지 않는다. 한 번 더 지워 보고, 그래도 실패하면 error 로그만 남긴다(대기 시간 뒤 같은 알림이 다시 갈 수 있다).
 *
 * <p>한 배치를 보내는 데 대기 시간이 다 지나면 다른 replica가 아직 보내지 않은 행을 다시 골라 중복 발송할 수 있다.
 * 경과 시간이 {@link #SEND_DEADLINE}에 닿으면 남은 행은 보내지 않고 바로 다시 고를 수 있게 놓아 다음 실행에 넘긴다.
 */
@Component
public class EmailChangeNotificationJob {

    private static final Logger log = LoggerFactory.getLogger(EmailChangeNotificationJob.class);
    private static final int BATCH_SIZE = 20;
    /** SMTP timeout(연결·읽기·쓰기 각 5초)보다 넉넉히 길어야 발송 중인 행을 다른 replica가 다시 고르지 않는다. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);
    /** 배치 안에서 새 발송을 시작하지 않는 경과 시간. 마지막 발송이 SMTP timeout까지 걸려도 대기 시간 안에 끝나게 1분을 남긴다. */
    static final Duration SEND_DEADLINE = CLAIM_LEASE.minus(Duration.ofMinutes(1));
    /** 2배씩 늘리는 간격이 넘치지 않도록 지수에 둔 상한. */
    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final JdbcTemplate jdbc;
    private final EmailChangeNotificationSender sender;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Clock clock;

    @Autowired
    public EmailChangeNotificationJob(
            JdbcTemplate jdbc,
            EmailChangeNotificationSender sender,
            @Value("${app.auth.email-change-notification.max-attempts:5}") int maxAttempts,
            @Value("${app.auth.email-change-notification.initial-backoff:1m}") Duration initialBackoff) {
        this(jdbc, sender, maxAttempts, initialBackoff, Clock.systemUTC());
    }

    EmailChangeNotificationJob(JdbcTemplate jdbc, EmailChangeNotificationSender sender, int maxAttempts,
                               Duration initialBackoff, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${app.auth.email-change-notification.poll-delay-ms:10000}",
            fixedDelayString = "${app.auth.email-change-notification.poll-delay-ms:10000}")
    public void runScheduled() {
        run(Instant.now());
    }

    public void run(Instant now) {
        Instant startedAt = clock.instant();
        List<Notification> claimed = claimDue(now);
        for (int i = 0; i < claimed.size(); i++) {
            if (Duration.between(startedAt, clock.instant()).compareTo(SEND_DEADLINE) >= 0) {
                release(claimed.subList(i, claimed.size()));
                return;
            }
            Notification notification = claimed.get(i);
            try {
                sender.send(notification.recipientEmail(), notification.maskedNewEmail(), notification.changedAt());
            } catch (RuntimeException e) {
                recordFailure(notification, clock.instant(), e);
                continue;
            }
            delete(notification);
        }
    }

    /** 발송은 끝났으므로 지우지 못해도 발송 실패로 세지 않는다. 일시적인 DB 오류일 수 있어 한 번 더 지워 본다. */
    private void delete(Notification notification) {
        RuntimeException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                jdbc.update("DELETE FROM email_change_notifications WHERE id = ?", notification.id());
                log.info("[이메일 변경 알림 발송 완료] id={} userId={} attempts={}",
                        notification.id(), notification.userId(), notification.attempts());
                return;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        log.error("[이메일 변경 알림 발송 완료, 삭제 실패] id={} userId={} attempts={} 대기 시간 뒤 다시 발송될 수 있다",
                notification.id(), notification.userId(), notification.attempts(), failure);
    }

    /** 보내지 않은 행은 claim에서 올린 시도 횟수를 되돌리고 바로 다시 고를 수 있게 둔다. */
    private void release(List<Notification> remaining) {
        Timestamp now = Timestamp.from(clock.instant());
        for (Notification notification : remaining) {
            jdbc.update("""
                    UPDATE email_change_notifications SET attempts = attempts - 1, next_attempt_at = ?
                    WHERE id = ? AND status = 'pending'
                    """, now, notification.id());
        }
        log.warn("[이메일 변경 알림 배치 중단] reason=lease_deadline released={} ids={}",
                remaining.size(), remaining.stream().map(Notification::id).toList());
    }

    /** 단일 UPDATE 문이라 그 자체로 커밋되며, 행 잠금은 이 문장 동안만 잡는다. */
    private List<Notification> claimDue(Instant now) {
        return jdbc.query("""
                UPDATE email_change_notifications
                SET attempts = attempts + 1, next_attempt_at = ?
                WHERE id IN (
                    SELECT id FROM email_change_notifications
                    WHERE status = 'pending' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, user_id, recipient_email, masked_new_email, changed_at, attempts
                """, (rs, n) -> new Notification(rs.getLong("id"), rs.getString("user_id"),
                        rs.getString("recipient_email"), rs.getString("masked_new_email"),
                        rs.getTimestamp("changed_at").toInstant(), rs.getInt("attempts")),
                Timestamp.from(now.plus(CLAIM_LEASE)), Timestamp.from(now), BATCH_SIZE);
    }

    private void recordFailure(Notification notification, Instant now, RuntimeException error) {
        String message = String.valueOf(error.getMessage());
        String lastError = message.length() > 1000 ? message.substring(0, 1000) : message;
        if (notification.attempts() >= maxAttempts) {
            jdbc.update("UPDATE email_change_notifications SET status = 'failed', last_error = ? WHERE id = ?",
                    lastError, notification.id());
            log.error("[이메일 변경 알림 발송 포기] id={} userId={} attempts={}",
                    notification.id(), notification.userId(), notification.attempts(), error);
            return;
        }
        Instant nextAttemptAt = now.plus(backoff(notification.attempts()));
        jdbc.update("UPDATE email_change_notifications SET next_attempt_at = ?, last_error = ? WHERE id = ?",
                Timestamp.from(nextAttemptAt), lastError, notification.id());
        log.warn("[이메일 변경 알림 발송 실패, 다시 시도] id={} userId={} attempts={} nextAttemptAt={} error={}",
                notification.id(), notification.userId(), notification.attempts(), nextAttemptAt, message);
    }

    /** n번째 시도가 실패하면 첫 간격 × 2^(n-1) 뒤에 다시 시도한다(기본 1·2·4·8분). */
    private Duration backoff(int attempts) {
        return initialBackoff.multipliedBy(1L << Math.min(attempts - 1, MAX_BACKOFF_EXPONENT));
    }

    private record Notification(long id, String userId, String recipientEmail, String maskedNewEmail,
                                Instant changedAt, int attempts) {}
}
