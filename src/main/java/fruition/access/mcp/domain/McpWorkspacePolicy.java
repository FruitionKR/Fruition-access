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

@Entity
@Table(name = "mcp_workspace_policies")
public class McpWorkspacePolicy {
    @Id
    @Column(name = "workspace_id")
    private String workspaceId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private long generation;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_workspace_policy_scopes",
            joinColumns = @JoinColumn(name = "workspace_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private Set<McpScope> allowedScopes = EnumSet.noneOf(McpScope.class);

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpWorkspacePolicy() {}

    public McpWorkspacePolicy(String workspaceId, Instant now) {
        this.workspaceId = requireId(workspaceId);
        this.enabled = false;
        this.generation = 1;
        this.updatedAt = now;
    }

    public boolean update(boolean enabled, Set<McpScope> scopes, Instant now) {
        Set<McpScope> next = copy(scopes);
        if (this.enabled == enabled && allowedScopes.equals(next)) {
            return false;
        }
        this.enabled = enabled;
        this.allowedScopes.clear();
        this.allowedScopes.addAll(next);
        this.generation = Math.addExact(generation, 1);
        this.updatedAt = now;
        return true;
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workspace_id is required");
        }
        return value;
    }

    private static Set<McpScope> copy(Set<McpScope> scopes) {
        if (scopes == null) {
            throw new IllegalArgumentException("allowed scopes are required");
        }
        return scopes.isEmpty() ? EnumSet.noneOf(McpScope.class) : EnumSet.copyOf(scopes);
    }

    public String getWorkspaceId() { return workspaceId; }
    public boolean isEnabled() { return enabled; }
    public long getGeneration() { return generation; }
    public Set<McpScope> getAllowedScopes() { return Set.copyOf(allowedScopes); }
    public Instant getUpdatedAt() { return updatedAt; }
}
