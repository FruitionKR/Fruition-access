package fruition.access.mcp.oauth;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.REDIRECT_NOT_REGISTERED;

@Component
public final class McpRedirectValidator {
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1");

    public ValidatedMcpRedirect validate(McpClientMetadata client, String requestedUri) {
        URI requested = parseRedirect(requestedUri);
        for (String registeredValue : client.redirectUris()) {
            if (registeredValue.equals(requestedUri)) {
                return new ValidatedMcpRedirect(requestedUri, requested.getHost(), false);
            }
            URI registered = parseRedirect(registeredValue);
            if (isLoopback(registered) && sameExceptPort(registered, requested)) {
                return new ValidatedMcpRedirect(requestedUri, requested.getHost(), true);
            }
        }
        throw new McpClientMetadataException(REDIRECT_NOT_REGISTERED, "redirect URI is not registered");
    }

    static URI parseRegisteredRedirect(String value) {
        return parseRedirect(value);
    }

    private static URI parseRedirect(String value) {
        try {
            URI uri = URI.create(value);
            boolean loopback = isLoopback(uri);
            boolean validScheme = "https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme()));
            if (!uri.isAbsolute() || !validScheme || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getRawPath() == null || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (RuntimeException invalid) {
            throw new McpClientMetadataException(REDIRECT_NOT_REGISTERED, "redirect URI is invalid");
        }
    }

    private static boolean sameExceptPort(URI registered, URI requested) {
        return isLoopback(requested)
                && registered.getScheme().equals(requested.getScheme())
                && registered.getHost().equals(requested.getHost())
                && registered.getRawPath().equals(requested.getRawPath())
                && equals(registered.getRawQuery(), requested.getRawQuery());
    }

    private static boolean isLoopback(URI uri) {
        return LOOPBACK_HOSTS.contains(uri.getHost());
    }

    private static boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
