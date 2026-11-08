package fruition.access.mcp.controller;

import fruition.access.mcp.dto.McpWorkspacePolicyRequest;
import fruition.access.mcp.dto.McpWorkspacePolicyResponse;
import fruition.access.mcp.service.McpWorkspacePolicyManagementService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/mcp-policy")
@Tag(name = "MCP Workspace Policy", description = "OWNER-only workspace MCP policy management")
public class McpWorkspacePolicyController {
    private final McpWorkspacePolicyManagementService policies;

    public McpWorkspacePolicyController(McpWorkspacePolicyManagementService policies) {
        this.policies = policies;
    }

    @Operation(operationId = "getMcpWorkspacePolicy", summary = "Read a workspace's MCP policy")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current policy or the disabled default",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = McpWorkspacePolicyResponse.class))),
            @ApiResponse(responseCode = "401", description = "Web authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Current member is not an OWNER",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace not found or actor is not a member",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<McpWorkspacePolicyResponse> get(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Workspace ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(policies.get(userId, workspaceId));
    }

    @Operation(operationId = "replaceMcpWorkspacePolicy", summary = "Replace a workspace's MCP policy")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated policy",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = McpWorkspacePolicyResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid policy body",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Web authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Current member is not an OWNER",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace not found or actor is not a member",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    public ResponseEntity<McpWorkspacePolicyResponse> update(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Workspace ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Valid @RequestBody McpWorkspacePolicyRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(policies.update(userId, workspaceId, request));
    }
}
