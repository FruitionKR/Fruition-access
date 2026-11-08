package fruition.access.mcp.oauth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import fruition.access.mcp.domain.McpClientIds;
import fruition.access.mcp.domain.McpClientPolicy;
import fruition.access.mcp.repository.McpClientPolicyRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.transaction.annotation.Transactional;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.CLIENT_NOT_ALLOWED;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.INVALID_METADATA;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.POLICY_UNAVAILABLE;

public class McpClientMetadataResolver {
    private static final Set<String> REQUIRED_GRANTS = Set.of("authorization_code", "refresh_token");
    private static final Set<String> SECRET_AUTH_METHODS = Set.of(
            "client_secret_basic", "client_secret_post", "client_secret_jwt");

    private final McpClientPolicyRepository clients;
    private final CimdDocumentFetcher fetcher;
    private final ObjectReader json;

    McpClientMetadataResolver(McpClientPolicyRepository clients, CimdDocumentFetcher fetcher,
                              ObjectMapper objectMapper) {
        this.clients = clients;
        this.fetcher = fetcher;
        this.json = objectMapper.readerFor(JsonNode.class)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Transactional(readOnly = true, timeout = 3)
    public McpClientMetadata resolve(String clientId) {
        final String exactClientId;
        try {
            exactClientId = McpClientIds.requireValid(clientId);
        } catch (IllegalArgumentException invalid) {
            throw new McpClientMetadataException(CLIENT_NOT_ALLOWED, "OAuth client is not allowed");
        }

        final boolean enabled;
        try {
            enabled = clients.findById(exactClientId).filter(McpClientPolicy::isEnabled).isPresent();
        } catch (RuntimeException unavailable) {
            throw new McpClientMetadataException(POLICY_UNAVAILABLE, "OAuth client policy is unavailable");
        }
        if (!enabled) {
            throw new McpClientMetadataException(CLIENT_NOT_ALLOWED, "OAuth client is not allowed");
        }
        return parse(exactClientId, fetcher.fetch(exactClientId));
    }

    private McpClientMetadata parse(String exactClientId, byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject() || !exactClientId.equals(requiredText(root, "client_id"))) {
                throw invalid();
            }
            Optional<String> displayName = safeDisplayName(root.get("client_name"));
            List<String> redirects = redirectUris(root.get("redirect_uris"));
            Set<String> redirectHosts = new LinkedHashSet<>();
            for (String redirect : redirects) {
                redirectHosts.add(McpRedirectValidator.parseRegisteredRedirect(redirect).getHost());
            }
            String authMethod = tokenEndpointAuthMethod(root);
            Set<String> grantTypes = stringSet(root.get("grant_types"));
            if (!grantTypes.containsAll(REQUIRED_GRANTS)) throw invalid();
            JsonNode responseTypes = root.get("response_types");
            if (responseTypes != null && !stringSet(responseTypes).contains("code")) throw invalid();
            rejectCredentialMaterial(root);
            return new McpClientMetadata(exactClientId, displayName, redirects, redirectHosts,
                    authMethod, grantTypes);
        } catch (McpClientMetadataException invalid) {
            if (invalid.reason() == INVALID_METADATA) throw invalid;
            throw invalid();
        } catch (IOException | RuntimeException invalid) {
            throw invalid();
        }
    }

    private static List<String> redirectUris(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) throw invalid();
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
            McpRedirectValidator.parseRegisteredRedirect(value.textValue());
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }

    private static String tokenEndpointAuthMethod(JsonNode root) {
        JsonNode singular = root.get("token_endpoint_auth_method");
        String method = singular == null ? "none" : requiredText(root, "token_endpoint_auth_method");
        if (!"none".equals(method)) throw invalid();

        JsonNode plural = root.get("token_endpoint_auth_methods_supported");
        if (plural != null) {
            Set<String> methods = stringSet(plural);
            if (!methods.contains(method) || methods.stream().anyMatch(SECRET_AUTH_METHODS::contains)) {
                throw invalid();
            }
        }
        return method;
    }

    private static void rejectCredentialMaterial(JsonNode root) {
        if (root.has("client_secret") || root.has("client_secret_expires_at")) throw invalid();
        JsonNode jwks = root.get("jwks");
        if (jwks != null && root.has("jwks_uri")) throw invalid();
        if (jwks != null) {
            JsonNode keys = jwks.get("keys");
            if (keys == null || !keys.isArray()) throw invalid();
            for (JsonNode key : keys) {
                if (!key.isObject() || key.has("d") || key.has("p") || key.has("q") || key.has("dp")
                        || key.has("dq") || key.has("qi") || key.has("k")) {
                    throw invalid();
                }
            }
        }
    }

    private static Optional<String> safeDisplayName(JsonNode node) {
        if (node == null) return Optional.empty();
        if (!node.isTextual()) throw invalid();
        String value = node.textValue().strip();
        if (value.isEmpty() || value.codePoints().anyMatch(McpClientMetadataResolver::unsafeDisplayCodePoint)) {
            throw invalid();
        }
        return Optional.of(value);
    }

    private static boolean unsafeDisplayCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static Set<String> stringSet(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) throw invalid();
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
            values.add(value.textValue());
        }
        return Set.copyOf(values);
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }

    private static McpClientMetadataException invalid() {
        return new McpClientMetadataException(INVALID_METADATA, "CIMD metadata is invalid");
    }
}
