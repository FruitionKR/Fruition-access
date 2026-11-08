package fruition.access.mcp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mcp_client_policies")
public class McpClientPolicy {
    @Id
    @Column(name = "client_id", columnDefinition = "text")
    private String clientId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpClientPolicy() {}

    public boolean changeEnabled(boolean enabled, Instant now) {
        if (this.enabled == enabled) {
            return false;
        }
        this.enabled = enabled;
        this.updatedAt = now;
        return true;
    }

    public String getClientId() { return clientId; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
