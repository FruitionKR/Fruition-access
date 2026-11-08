package fruition.access.mcp.controller;

import fruition.access.mcp.dto.McpConnectionListResponse;
import fruition.access.mcp.service.McpConnectionService;
import fruition.shared.i18n.RequestLocale;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.context.MessageSource;
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
@RequestMapping("/api/mcp/connections")
@Tag(name = "MCP Connections", description = "Authenticated user's MCP connections")
public class McpConnectionController {
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final McpConnectionService connections;
    private final MessageSource messageSource;

    public McpConnectionController(McpConnectionService connections, MessageSource messageSource) {
        this.connections = connections;
        this.messageSource = messageSource;
    }

    @Operation(operationId = "listMcpConnections", summary = "List the authenticated user's MCP connections")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User-scoped connection page",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = McpConnectionListResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid page or size",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Web authentication required", content = @Content)
    })
    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal String userId,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100"))
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE || (long) page * size > Integer.MAX_VALUE) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    "INVALID_REQUEST", message("error.INVALID_REQUEST.mcp_connection_page_size")));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(connections.list(userId, page, size));
    }

    @Operation(operationId = "disconnectMcpConnection",
            summary = "Disconnect one of the authenticated user's MCP connections")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Disconnected or already disconnected",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid grant ID",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Web authentication required", content = @Content),
            @ApiResponse(responseCode = "404", description = "Connection not found", content = @Content)
    })
    @DeleteMapping("/{grant_id}")
    public ResponseEntity<?> disconnect(
            @AuthenticationPrincipal String userId,
            @Parameter(description = "Server-generated UUIDv4 grant ID", required = true,
                    schema = @Schema(format = "uuid"))
            @PathVariable("grant_id") String grantId) {
        UUID parsed = parseGrantId(grantId);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    "INVALID_REQUEST", message("error.INVALID_REQUEST.mcp_connection_grant_id")));
        }
        return connections.disconnect(userId, parsed)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
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
