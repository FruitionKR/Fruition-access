package fruition.access.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import fruition.access.mcp.domain.McpScope;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"workspace_id", "enabled", "allowed_scopes", "generation", "updated_at"})
public record McpWorkspacePolicyResponse(
        @JsonProperty("workspace_id") String workspaceId,
        boolean enabled,
        @JsonProperty("allowed_scopes") @ArraySchema(uniqueItems = true) List<McpScope> allowedScopes,
        @Schema(types = {"integer", "null"}, format = "int64", minimum = "1") Long generation,
        @JsonProperty("updated_at")
        @Schema(types = {"string", "null"}, format = "date-time") Instant updatedAt
) {}
