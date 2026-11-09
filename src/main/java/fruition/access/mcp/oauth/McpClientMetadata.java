package fruition.access.mcp.oauth;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable, unverified client registration material for the future SAS adapter and consent UI. */
public record McpClientMetadata(
        String clientId,
        Optional<String> displayName,
        List<String> redirectUris,
        Set<String> redirectHosts,
        String tokenEndpointAuthMethod,
        Set<String> grantTypes
) {
    public McpClientMetadata {
        displayName = displayName == null ? Optional.empty() : displayName;
        redirectUris = List.copyOf(redirectUris);
        redirectHosts = Set.copyOf(redirectHosts);
        grantTypes = Set.copyOf(grantTypes);
    }

    public boolean identityVerified() {
        return false;
    }
}
