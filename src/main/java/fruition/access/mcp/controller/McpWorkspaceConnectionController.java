package fruition.access.mcp.controller;

import fruition.access.mcp.dto.McpWorkspaceConnectionListResponse;
import fruition.access.mcp.service.McpWorkspaceConnectionManagementService;
import fruition.shared.i18n.RequestLocale;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.MessageSource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspace_id}/mcp-connections")
@Tag(name = "MCP Workspace Connections", description = "OWNER-only workspace MCP connection management")
public class McpWorkspaceConnectionController {
    private static final int MAX_SIZE = 100;

    private final McpWorkspaceConnectionManagementService connections;
    private final MessageSource messageSource;

    public McpWorkspaceConnectionController(McpWorkspaceConnectionManagementService connections,
                                            MessageSource messageSource) {
        this.connections = connections;
        this.messageSource = messageSource;
    }

    @Operation(operationId = "listWorkspaceMcpConnections", summary = "List a workspace's MCP connections")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workspace-scoped connection page",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = McpWorkspaceConnectionListResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid page or size",
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
    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Workspace ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100"))
            @RequestParam(defaultValue = "20") int size) {
        if (!validPage(page, size)) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    "INVALID_REQUEST", message("error.INVALID_REQUEST.mcp_connection_page_size")));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(connections.list(userId, workspaceId, page, size));
    }

    @Operation(operationId = "revokeWorkspaceMcpConnection",
            summary = "Administratively revoke a workspace MCP connection")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Revoked or already sufficiently revoked",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid grant ID",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Web authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Current member is not an OWNER",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace, membership, or connection not found",
                    content = @Content)
    })
    @DeleteMapping("/{grant_id}")
    public ResponseEntity<?> revoke(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Workspace ID", example = "ws_abc12345")
            @PathVariable("workspace_id") String workspaceId,
            @Parameter(description = "Server-generated UUIDv4 grant ID", required = true,
                    schema = @Schema(format = "uuid"))
            @PathVariable("grant_id") String grantId) {
        UUID parsed = parseGrantId(grantId);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    "INVALID_REQUEST", message("error.INVALID_REQUEST.mcp_connection_grant_id")));
        }
        return connections.revoke(userId, workspaceId, parsed)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static boolean validPage(int page, int size) {
        return page >= 0 && size >= 1 && size <= MAX_SIZE && (long) page * size <= Integer.MAX_VALUE;
    }

    private static UUID parseGrantId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.version() == 4 && parsed.toString().equals(value.toLowerCase(Locale.ROOT)) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, RequestLocale.current().toLocale());
    }
}
