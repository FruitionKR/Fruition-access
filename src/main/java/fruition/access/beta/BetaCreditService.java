package fruition.access.beta;

import fruition.access.workspace.service.DocumentInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class BetaCreditService {
    private static final Logger log = LoggerFactory.getLogger(BetaCreditService.class);
    private final JdbcTemplate jdbc;
    private final DocumentInternalClient document;
    private final TransactionTemplate independent;
    private final org.springframework.scheduling.TaskScheduler scheduler;

    public BetaCreditService(JdbcTemplate jdbc, DocumentInternalClient document, PlatformTransactionManager manager, org.springframework.scheduling.TaskScheduler scheduler) {
        this.jdbc = jdbc;
        this.document = document;
        this.scheduler = scheduler;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UserSignedUp event) {
        var campaign = jdbc.queryForMap("SELECT enabled, starts_at, allocated FROM beta_credit_campaign WHERE id = 1 FOR UPDATE");
        Timestamp start = (Timestamp) campaign.get("starts_at");
        if (!Boolean.TRUE.equals(campaign.get("enabled")) || start == null || event.createdAt().isBefore(start.toInstant())) return;
        int allocated = ((Number) campaign.get("allocated")).intValue();
        if (allocated >= 50) return;
        int inserted = jdbc.update("""
                INSERT INTO beta_credit_awards(user_id, email_hash, slot) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """, event.userId(), emailHash(event.email()), allocated + 1);
        if (inserted == 1) jdbc.update("UPDATE beta_credit_campaign SET allocated = allocated + 1 WHERE id = 1");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliverAfterSignup(UserSignedUp event) {
        // 가입 응답을 외부 서비스 지연에 묶지 않는다. 예약 실패도 outbox 재시도가 복구한다.
        try {
            scheduler.schedule(() -> deliver(event.userId()), java.time.Instant.now());
        } catch (RuntimeException failure) {
            log.warn("[베타 크레딧 즉시 지급 예약 실패] userId={}", event.userId());
        }
    }

    @Scheduled(initialDelayString = "${app.beta-credit.retry-ms:30000}", fixedDelayString = "${app.beta-credit.retry-ms:30000}")
    public void retryPending() {
        for (String id : jdbc.queryForList("SELECT user_id FROM beta_credit_awards WHERE delivered_at IS NULL "
                + "AND next_attempt_at <= now() ORDER BY slot LIMIT 10", String.class)) deliver(id);
    }

    public void deliver(String userId) {
        try {
            var rows = independent.execute(tx -> jdbc.queryForList("""
                    UPDATE beta_credit_awards SET next_attempt_at = now() + interval '1 minute'
                    WHERE user_id = ? AND delivered_at IS NULL AND next_attempt_at <= now()
                    RETURNING slot, email_hash
                    """, userId));
            if (rows == null || rows.isEmpty()) return;
            var row = rows.getFirst();
            long amount = document.grantBetaCredit(userId, (String) row.get("email_hash"), ((Number) row.get("slot")).intValue());
            if (amount <= 0) throw new IllegalStateException("Invalid beta credit grant amount");
            independent.executeWithoutResult(tx -> jdbc.update("UPDATE beta_credit_awards SET delivered_at = now(), "
                    + "amount_krw_milli = ? WHERE user_id = ? AND delivered_at IS NULL", amount, userId));
        } catch (RuntimeException failure) {
            log.warn("[베타 크레딧 지급 재시도 대기] userId={} cause={}", userId, failure.getClass().getSimpleName());
        }
    }

    public Status status(String userId) {
        var award = jdbc.queryForList("SELECT delivered_at, amount_krw_milli FROM beta_credit_awards WHERE user_id = ?", userId);
        if (!award.isEmpty()) {
            var row = award.getFirst();
            return new Status(row.get("delivered_at") == null ? "pending" : "granted",
                    row.get("amount_krw_milli") == null ? null : ((Number) row.get("amount_krw_milli")).longValue());
        }
        var rows = jdbc.queryForList("SELECT u.created_at, c.starts_at, c.enabled, c.allocated FROM users u "
                + "CROSS JOIN beta_credit_campaign c WHERE u.id = ? AND c.id = 1", userId);
        if (rows.isEmpty()) return new Status("ineligible", null);
        var row = rows.getFirst();
        Timestamp start = (Timestamp) row.get("starts_at");
        if (start == null || ((Timestamp) row.get("created_at")).before(start)) return new Status("ineligible", null);
        return new Status(((Number) row.get("allocated")).intValue() >= 50
                ? "exhausted" : "ineligible", null);
    }

    static String emailHash(String email) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(email.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Status(String status, @com.fasterxml.jackson.annotation.JsonProperty("amount_krw_milli") Long amountKrwMilli) {}
}
