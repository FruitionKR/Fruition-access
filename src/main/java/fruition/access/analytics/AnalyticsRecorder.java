package fruition.access.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class AnalyticsRecorder {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsRecorder.class);
    private static final UUID NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final AnalyticsSettings settings;

    public AnalyticsRecorder(DataSource dataSource, ObjectMapper mapper, AnalyticsSettings settings) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.settings = settings;
    }

    public static UUID eventId(String event, String logicalKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16).putLong(NAMESPACE.getMostSignificantBits())
                    .putLong(NAMESPACE.getLeastSignificantBits()).array());
            byte[] hash = sha1.digest(("fruition.analytics.v1:" + event + ":" + logicalKey)
                    .getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // Deliberately no transactional interceptor: an analytics SQL exception must not mark
    // the enclosing JPA product transaction rollback-only. JDBC uses its bound connection.
    @EventListener
    public void record(AnalyticsEvent event) {
        if (!settings.enabled()) return;
        Connection connection = null;
        Savepoint savepoint = null;
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("analytics_requires_product_transaction");
            }
            validate(event);
            UUID id = eventId(event.event(), event.logicalKey());
            Map<String, Object> properties = new LinkedHashMap<>(event.properties());
            properties.put("event_id", id.toString());
            properties.put("schema_version", 1);
            properties.put("event_source", "access_server");
            properties.put("environment", settings.environment());
            properties.put("app_version", settings.appVersion());
            if (event.workspaceId() != null) {
                properties.put("workspace_id", event.workspaceId());
                properties.put("$groups", Map.of("workspace", event.workspaceId()));
            }
            String json = mapper.writeValueAsString(properties);
            connection = DataSourceUtils.getConnection(dataSource);
            if (connection.getAutoCommit()) throw new IllegalStateException("unbound_connection");
            savepoint = connection.setSavepoint();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO analytics_outbox(uuid, event, distinct_id, occurred_at, properties)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (uuid) DO NOTHING
                    """)) {
                statement.setQueryTimeout(2);
                statement.setObject(1, id);
                statement.setString(2, event.event());
                statement.setString(3, event.actorId());
                statement.setTimestamp(4, Timestamp.from(event.occurredAt()));
                statement.setString(5, json);
                statement.executeUpdate();
            }
        } catch (Exception failure) {
            if (connection != null && savepoint != null) {
                try { connection.rollback(savepoint); }
                catch (Exception rollbackFailure) { log.warn("analytics_record_failed code=savepoint_rollback"); }
            }
            // Never include exceptions, payloads or identifiers in diagnostics.
            log.warn("analytics_record_failed code=record_rejected");
        } finally {
            if (connection != null) {
                if (savepoint != null) {
                    try { connection.releaseSavepoint(savepoint); }
                    catch (Exception ignored) { log.warn("analytics_record_failed code=savepoint_release"); }
                }
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    private void validate(AnalyticsEvent event) {
        if (!Set.of("local", "staging", "production").contains(settings.environment())
                || settings.appVersion().isBlank() || settings.appVersion().length() > 128
                || !settings.appVersion().matches("[A-Za-z0-9._+-]+")
                || !identifier(event.actorId()) || !identifier(event.logicalKey()) || event.occurredAt() == null) {
            throw new IllegalArgumentException("invalid_envelope");
        }
        if (event.event().equals("user_signed_up")) {
            if (event.workspaceId() != null || !event.properties().keySet().equals(Set.of("method"))
                    || !Set.of("email", "google", "naver", "kakao").contains(event.properties().get("method"))) {
                throw new IllegalArgumentException("invalid_signup");
            }
        } else if (Set.of("member_invited", "member_joined").contains(event.event())) {
            if (!identifier(event.workspaceId()) || !event.properties().keySet().equals(Set.of("invitation_id", "role"))
                    || !identifier(event.properties().get("invitation_id"))
                    || !Set.of("owner", "member").contains(event.properties().get("role"))) {
                throw new IllegalArgumentException("invalid_membership");
            }
        } else throw new IllegalArgumentException("unknown_event");
    }

    private boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }
}
