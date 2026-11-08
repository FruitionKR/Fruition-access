package fruition.access.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.mcp.domain.McpGrantRevocationReason;
import fruition.access.mcp.domain.McpGrantStatus;
import fruition.access.mcp.domain.McpScope;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {"connections", "page", "size", "has_next"})
public record McpConnectionListResponse(
        List<Connection> connections,
        int page,
        int size,
        @JsonProperty("has_next") boolean hasNext
) {
    @Schema(requiredProperties = {"grant_id", "workspace_id", "client_id", "scopes", "status",
            "revocation_reason", "created_at", "revoked_at"})
    public record Connection(
            @JsonProperty("grant_id") UUID grantId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("client_id") String clientId,
            List<McpScope> scopes,
            McpGrantStatus status,
            @JsonProperty("revocation_reason")
            @Schema(types = {"string", "null"}) McpGrantRevocationReason revocationReason,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("revoked_at")
            @Schema(types = {"string", "null"}, format = "date-time") Instant revokedAt
    ) {}
}
