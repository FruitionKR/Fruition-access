package fruition.access.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Dedicated lifecycle-owned thread; never performs network I/O on the product request thread. */
@Component
public class AnalyticsRelay implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsRelay.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AnalyticsSettings settings;
    private final TransactionTemplate transaction;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public AnalyticsRelay(JdbcTemplate jdbc, ObjectMapper mapper, AnalyticsSettings settings,
                          PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.settings = settings;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public synchronized void start() {
        if (running || !settings.relayEnabled()) return;
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "analytics-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> runOnce(Instant.now()), 10, 10, TimeUnit.SECONDS);
    }

    @Override public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(6, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            executor = null;
        }
    }
    @Override public boolean isRunning() { return running; }

    public void runOnce(Instant now) {
        if (!settings.relayEnabled()) return;
        try {
            transaction.executeWithoutResult(status -> dispatch(now));
        } catch (Exception failure) {
            // A failed ACK update rolls back: the same UUID and envelope are retried.
            log.warn("analytics_relay_failed code=storage_error");
        }
    }

    private void dispatch(Instant now) {
        jdbc.update("DELETE FROM analytics_outbox WHERE sent_at < ?", Timestamp.from(now.minus(Duration.ofDays(7))));
        var rows = jdbc.queryForList("""
                SELECT uuid, event, distinct_id, occurred_at, properties::text AS properties, attempts
                FROM analytics_outbox WHERE sent_at IS NULL AND NOT dead AND next_attempt_at <= ?
                ORDER BY next_attempt_at, uuid LIMIT 100 FOR UPDATE SKIP LOCKED
                """, Timestamp.from(now));
        if (rows.isEmpty()) return;
        String errorCode = null;
        try {
            var batch = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                batch.add(Map.of("uuid", row.get("uuid").toString(), "event", row.get("event"),
                        "distinct_id", row.get("distinct_id"),
                        "timestamp", ((Timestamp) row.get("occurred_at")).toInstant().toString(),
                        "properties", mapper.readTree((String) row.get("properties"))));
            }
            String body = mapper.writeValueAsString(Map.of("api_key", settings.token(), "batch", batch));
            URI endpoint = URI.create(settings.host().replaceAll("/+$", "") + "/batch/");
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code < 200 || code >= 300) errorCode = "http_error";
        } catch (java.net.http.HttpTimeoutException timeout) {
            errorCode = "timeout";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            errorCode = "interrupted";
        } catch (Exception failure) {
            errorCode = "transport_error";
        }
        for (var row : rows) {
            if (errorCode == null) {
                jdbc.update("UPDATE analytics_outbox SET sent_at = ?, last_error_code = NULL WHERE uuid = ?",
                        Timestamp.from(now), row.get("uuid"));
            } else {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                long delay = Math.min(3600L, 60L * (1L << Math.min(attempts - 1, 6)));
                jdbc.update("""
                        UPDATE analytics_outbox SET attempts = ?, next_attempt_at = ?, last_error_code = ?, dead = ?
                        WHERE uuid = ?
                        """, attempts, Timestamp.from(now.plusSeconds(delay)), errorCode, attempts >= 24, row.get("uuid"));
                if (attempts >= 24) log.warn("analytics_relay_failed code=dead");
            }
        }
    }
}
