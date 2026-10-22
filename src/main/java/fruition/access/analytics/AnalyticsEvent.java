package fruition.access.analytics;

import java.time.Instant;
import java.util.Map;

/** Only internal identifiers and explicitly allowed categorical properties cross this boundary. */
public record AnalyticsEvent(String event, String logicalKey, String actorId, String workspaceId,
                             Instant occurredAt, Map<String, String> properties) {
    public AnalyticsEvent {
        properties = Map.copyOf(properties);
    }
}
