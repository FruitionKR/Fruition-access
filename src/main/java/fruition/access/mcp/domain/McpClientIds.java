package fruition.access.mcp.domain;

import java.net.URI;

public final class McpClientIds {
    private McpClientIds() {}

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String rawPath = uri.getRawPath();
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && rawPath != null
                    && !rawPath.isEmpty()
                    && !"/".equals(rawPath)
                    && !hasDotSegment(rawPath);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean hasDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) return true;
        }
        return false;
    }

    public static String requireValid(String value) {
        if (!isValid(value)) {
            throw new IllegalArgumentException("client_id must be an HTTPS CIMD URL");
        }
        return value;
    }
}
