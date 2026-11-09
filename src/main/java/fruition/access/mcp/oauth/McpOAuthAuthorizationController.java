package fruition.access.mcp.oauth;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "app.mcp.oauth.enabled", havingValue = "true")
public class McpOAuthAuthorizationController {
    private static final Set<String> AUTHORIZATION_PARAMETERS = Set.of("response_type", "client_id",
            "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method", "resource");
    private final McpOAuthCredentialService credentials;

    public McpOAuthAuthorizationController(McpOAuthCredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping("/oauth2/authorize")
    public ResponseEntity<McpOAuthCredentialService.PendingAuthorization> preview(
            @AuthenticationPrincipal String userId, HttpServletRequest request, CsrfToken csrf) {
        csrf.getToken();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!AUTHORIZATION_PARAMETERS.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw new McpOAuthCredentialService.InvalidOAuthRequest("invalid_request");
            }
        }
        var pending = credentials.begin(userId, request.getSession(true).getId(), new McpOAuthCredentialService.AuthorizationRequest(
                request.getParameter("response_type"), request.getParameter("client_id"),
                request.getParameter("redirect_uri"), request.getParameter("scope"),
                request.getParameter("state"), request.getParameter("code_challenge"),
                request.getParameter("code_challenge_method"), request.getParameter("resource")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .header(csrf.getHeaderName(), csrf.getToken()).body(pending);
    }

    @PostMapping("/oauth2/authorize/{pendingId}")
    public ResponseEntity<McpOAuthCredentialService.AuthorizationDecision> decide(
            @AuthenticationPrincipal String userId, @PathVariable UUID pendingId,
            @Valid @RequestBody DecisionBody body, HttpServletRequest request) {
        return noStore(credentials.decide(userId, sessionId(request), pendingId,
                new McpOAuthCredentialService.DecisionRequest(body.decision(), body.workspaceId())));
    }

    @ExceptionHandler(McpOAuthCredentialService.InvalidOAuthRequest.class)
    public ResponseEntity<Map<String, String>> oauthError(McpOAuthCredentialService.InvalidOAuthRequest error) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error", error.error()));
    }

    @ExceptionHandler(McpClientMetadataException.class)
    public ResponseEntity<Map<String, String>> clientError() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("error", "invalid_request"));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    private static String sessionId(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            throw new McpOAuthCredentialService.InvalidOAuthRequest("invalid_request");
        }
        return request.getSession(false).getId();
    }

    public record DecisionBody(@NotNull McpOAuthCredentialService.Decision decision,
                               @JsonProperty("workspace_id") String workspaceId) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("Unknown field: " + field);
        }
    }
}
