package fruition.access.mcp.oauth;

public final class McpClientMetadataException extends RuntimeException {
    public enum Reason {
        CLIENT_NOT_ALLOWED,
        POLICY_UNAVAILABLE,
        DESTINATION_BLOCKED,
        REDIRECT_RESPONSE,
        RESPONSE_TOO_LARGE,
        TIMEOUT,
        FETCH_FAILED,
        INVALID_METADATA,
        REDIRECT_NOT_REGISTERED
    }

    private final Reason reason;

    McpClientMetadataException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
