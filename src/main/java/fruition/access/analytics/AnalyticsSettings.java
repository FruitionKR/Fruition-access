package fruition.access.analytics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

@Component
public record AnalyticsSettings(boolean enabled, String token, String host, String environment, String appVersion) {
    public AnalyticsSettings {
        // Analytics-only configuration must never prevent product startup.
        if (!Set.of("local", "staging", "production").contains(environment)
                || !appVersion.matches("[A-Za-z0-9._+-]{1,128}") || !validHost(host)) {
            enabled = false;
        }
    }

    @Autowired
    public AnalyticsSettings(
            @Value("${app.analytics.enabled:false}") String enabled,
            @Value("${app.analytics.project-token:}") String token,
            @Value("${app.analytics.host:https://us.i.posthog.com}") String host,
            @Value("${app.analytics.environment:local}") String environment,
            @Value("${app.analytics.app-version:unknown}") String appVersion) {
        this("true".equalsIgnoreCase(enabled), token, host, environment, appVersion);
    }

    private static boolean validHost(String host) {
        try {
            URI uri = URI.create(host);
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && ("https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "localhost", "[::1]", "host.docker.internal").contains(uri.getHost())));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    @Override public String toString() { return "AnalyticsSettings[enabled=" + enabled + "]"; }

    // Never send a personal API key (phx_) or an arbitrary credential to the ingestion endpoint.
    public boolean relayEnabled() { return enabled && token.matches("phc_[A-Za-z0-9_-]+"); }
}
