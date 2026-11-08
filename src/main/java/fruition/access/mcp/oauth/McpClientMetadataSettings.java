package fruition.access.mcp.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.mcp.oauth.client-metadata")
public record McpClientMetadataSettings(int maxResponseBytes, Duration timeout) {
    public McpClientMetadataSettings {
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("CIMD max response bytes must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(2)) > 0) {
            throw new IllegalArgumentException("CIMD timeout must be positive and at most 2 seconds");
        }
    }
}
