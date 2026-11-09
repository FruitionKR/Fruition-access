package fruition.access.mcp.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "mcp_grants")
public class McpGrant {
    @Id
    @Column(name = "grant_id")
    private UUID grantId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "client_id", nullable = false, columnDefinition = "text")
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private McpGrantStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason")
    private McpGrantRevocationReason revocationReason;

    @Column(name = "policy_generation", nullable = false)
    private long policyGeneration;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_grant_scopes", joinColumns = @JoinColumn(name = "grant_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private Set<McpScope> scopes = EnumSet.noneOf(McpScope.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected McpGrant() {}

    public McpGrant(UUID grantId, String userId, String workspaceId, String clientId,
                    long policyGeneration, Set<McpScope> scopes, Instant now) {
        if (grantId == null || grantId.version() != 4) {
            throw new IllegalArgumentException("grant_id must be UUIDv4");
        }
        if (policyGeneration < 1) {
            throw new IllegalArgumentException("policy_generation must be positive");
        }
        this.grantId = grantId;
        this.userId = requireId(userId, "user_id");
        this.workspaceId = requireId(workspaceId, "workspace_id");
        this.clientId = McpClientIds.requireValid(clientId);
        this.policyGeneration = policyGeneration;
        this.scopes.addAll(requireScopes(scopes));
        this.status = McpGrantStatus.ACTIVE;
        this.createdAt = now;
    }

    public boolean revoke(McpGrantRevocationReason reason, Instant now) {
        if (reason == null) {
            throw new IllegalArgumentException("revocation reason is required");
        }
        int current = revocationReason == null ? 0 : revocationReason.precedence();
        if (reason.precedence() <= current) {
            return false;
        }
        status = reason.status();
        revocationReason = reason;
        revokedAt = now;
        return true;
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Set<McpScope> requireScopes(Set<McpScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one grant scope is required");
        }
        return EnumSet.copyOf(scopes);
    }

    public UUID getGrantId() { return grantId; }
    public String getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getClientId() { return clientId; }
    public McpGrantStatus getStatus() { return status; }
    public McpGrantRevocationReason getRevocationReason() { return revocationReason; }
    public long getPolicyGeneration() { return policyGeneration; }
    public Set<McpScope> getScopes() { return Set.copyOf(scopes); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
