package fruition.access.mcp.controller;

import fruition.access.mcp.dto.McpAuthorizationRequest;
import fruition.access.mcp.dto.McpAuthorizationResponse;
import fruition.access.mcp.service.McpAuthorizationService;
import fruition.shared.util.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

@RestController
public class InternalMcpAuthorizationController {
    private final McpAuthorizationService authorizationService;

    public InternalMcpAuthorizationController(McpAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Operation(summary = "Authorize current MCP grant context and action")
    @SecurityRequirement(name = "internalToken")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current authorization decision",
                    content = @Content(schema = @Schema(implementation = McpAuthorizationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid grant ID or strict request schema",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid internal token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/internal/mcp/grants/{grant_id}/authorize")
    public ResponseEntity<?> authorize(
            @Parameter(description = "Server-generated UUIDv4 grant ID", required = true,
                    schema = @Schema(format = "uuid"))
            @PathVariable("grant_id") String grantId,
            @Valid @RequestBody McpAuthorizationRequest request) {
        UUID parsed = parseGrantId(grantId);
        if (parsed == null) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("INVALID_REQUEST", "grant_id 형식이 올바르지 않습니다."));
        }
        return ResponseEntity.ok(authorizationService.authorize(parsed, request));
    }

    private static UUID parseGrantId(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.version() == 4 && parsed.toString().equals(value.toLowerCase(Locale.ROOT)) ? parsed : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
