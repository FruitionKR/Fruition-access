package fruition.access.mcp.domain;

public enum McpGrantRevocationReason {
    USER_DISCONNECT(McpGrantStatus.USER_DISCONNECTED, 1),
    ADMIN_GRANT_REVOKE(McpGrantStatus.ADMIN_REVOKED, 2),
    WORKSPACE_POLICY_DISABLED(McpGrantStatus.ADMIN_REVOKED, 2),
    REFRESH_REUSE(McpGrantStatus.SECURITY_REVOKED, 3),
    CLIENT_DISABLED(McpGrantStatus.SECURITY_REVOKED, 3),
    USER_PURGED(McpGrantStatus.PURGED, 4),
    WORKSPACE_PURGED(McpGrantStatus.PURGED, 4);

    private final McpGrantStatus status;
    private final int precedence;

    McpGrantRevocationReason(McpGrantStatus status, int precedence) {
        this.status = status;
        this.precedence = precedence;
    }

    public McpGrantStatus status() {
        return status;
    }

    public int precedence() {
        return precedence;
    }
}
