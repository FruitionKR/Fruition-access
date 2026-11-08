package fruition.access.mcp.oauth;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public record McpOAuthSettings(URI issuer, URI resource, Duration authorizationCodeTtl,
                               Duration accessTokenTtl, Duration refreshTokenTtl,
                               Duration pendingAuthorizationTtl) {
    public McpOAuthSettings {
        requireHttps(issuer, "issuer");
        requireHttps(resource, "resource");
        if (issuer.getQuery() != null || issuer.getFragment() != null || resource.getQuery() != null
                || resource.getFragment() != null) {
            throw new IllegalArgumentException("MCP OAuth issuer/resource cannot contain query or fragment");
        }
        for (Duration ttl : List.of(authorizationCodeTtl, accessTokenTtl, refreshTokenTtl,
                pendingAuthorizationTtl)) {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("MCP OAuth TTLs must be positive");
            }
        }
    }

    private static void requireHttps(URI value, String name) {
        if (value == null || !"https".equals(value.getScheme()) || value.getHost() == null) {
            throw new IllegalArgumentException("MCP OAuth " + name + " must be an absolute HTTPS URI");
        }
    }
}
