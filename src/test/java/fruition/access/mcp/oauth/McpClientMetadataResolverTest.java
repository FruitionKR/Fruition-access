package fruition.access.mcp.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.access.mcp.domain.McpClientPolicy;
import fruition.access.mcp.repository.McpClientPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.CLIENT_NOT_ALLOWED;
import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.INVALID_METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpClientMetadataResolverTest {
    private static final String CLIENT_ID = "https://client.example/oauth/metadata?version=1";

    private McpClientPolicyRepository clients;
    private McpClientPolicy enabled;

    @BeforeEach
    void setUp() {
        clients = mock(McpClientPolicyRepository.class);
        enabled = mock(McpClientPolicy.class);
        when(enabled.isEnabled()).thenReturn(true);
    }

    @Test
    void unknownAndDisabledClientsDoNoNetworkWork() {
        AtomicInteger fetches = new AtomicInteger();
        var resolver = resolver(ignored -> {
            fetches.incrementAndGet();
            return validMetadata(CLIENT_ID);
        });
        McpClientPolicy disabled = mock(McpClientPolicy.class);
        when(disabled.isEnabled()).thenReturn(false);
        when(clients.findById(CLIENT_ID)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> resolver.resolve(CLIENT_ID))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(CLIENT_NOT_ALLOWED));
        assertThatThrownBy(() -> resolver.resolve("https://unknown.example/client"))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(CLIENT_NOT_ALLOWED));
        assertThat(fetches).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://client.example", "https://client.example/", "https://client.example/./metadata",
            "https://client.example/a/../metadata"
    })
    void invalidClientIdentifiersFailBeforePolicyLookupOrNetworkFetch(String clientId) {
        AtomicInteger fetches = new AtomicInteger();
        var resolver = resolver(ignored -> {
            fetches.incrementAndGet();
            return validMetadata(CLIENT_ID);
        });

        assertThatThrownBy(() -> resolver.resolve(clientId))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(CLIENT_NOT_ALLOWED));
        assertThat(fetches).hasValue(0);
        verifyNoInteractions(clients);
    }

    @Test
    void allowlistComparisonKeepsTheExactFullUrl() {
        AtomicInteger fetches = new AtomicInteger();
        when(clients.findById(CLIENT_ID)).thenReturn(Optional.of(enabled));
        var resolver = resolver(clientId -> {
            fetches.incrementAndGet();
            return validMetadata(clientId);
        });

        assertThat(resolver.resolve(CLIENT_ID).clientId()).isEqualTo(CLIENT_ID);
        for (String different : new String[]{
                "https://client.example/oauth/metadata?version=01",
                "https://client.example:443/oauth/metadata?version=1",
                "https://CLIENT.example/oauth/metadata?version=1",
                "https://client.example/oauth/metadata/?version=1"
        }) {
            assertThatThrownBy(() -> resolver.resolve(different))
                    .isInstanceOfSatisfying(McpClientMetadataException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(CLIENT_NOT_ALLOWED));
        }
        assertThat(fetches).hasValue(1);
        verify(clients).findById("https://client.example/oauth/metadata?version=01");
    }

    @Test
    void producesSmallUnverifiedSnapshotAndIgnoresExtensions() {
        when(clients.findById(CLIENT_ID)).thenReturn(Optional.of(enabled));
        String body = """
                {
                  "client_id": "%s",
                  "client_name": "  Claude Code  ",
                  "redirect_uris": ["https://claude.ai/api/mcp/auth_callback", "http://localhost/callback"],
                  "token_endpoint_auth_methods_supported": ["none", "private_key_jwt"],
                  "grant_types": ["authorization_code", "refresh_token", "urn:example:extension"],
                  "x_vendor_extension": {"accepted": true}
                }
                """.formatted(CLIENT_ID);

        McpClientMetadata result = resolver(ignored -> body.getBytes(StandardCharsets.UTF_8)).resolve(CLIENT_ID);

        assertThat(result.displayName()).contains("Claude Code");
        assertThat(result.redirectUris()).containsExactly(
                "https://claude.ai/api/mcp/auth_callback", "http://localhost/callback");
        assertThat(result.redirectHosts()).containsExactlyInAnyOrder("claude.ai", "localhost");
        assertThat(result.tokenEndpointAuthMethod()).isEqualTo("none");
        assertThat(result.grantTypes()).contains("authorization_code", "refresh_token", "urn:example:extension");
        assertThat(result.identityVerified()).isFalse();
    }

    @Test
    void rejectsMismatchMalformedCredentialMaterialAndMissingRequiredGrant() {
        when(clients.findById(CLIENT_ID)).thenReturn(Optional.of(enabled));
        assertInvalid("{not-json");
        assertInvalid(new String(validMetadata("https://other.example/client"), StandardCharsets.UTF_8));
        assertInvalid(baseMetadata("\"client_secret\":\"do-not-accept\","));
        assertInvalid(validString().replace("\"none\"", "\"client_secret_basic\""));
        assertInvalid(validString().replace("\"authorization_code\",\"refresh_token\"", "\"authorization_code\""));
        assertInvalid(baseMetadata("\"response_types\":[\"token\"],"));
        assertInvalid(validString().replace("\"Client\"", "\"unsafe\\nname\""));
        assertInvalid(validString().replace("https://client.example/callback", "https://*.example/callback"));
        assertInvalid(baseMetadata("\"client_id\":\"%s\",".formatted(CLIENT_ID)));
    }

    private void assertInvalid(String body) {
        assertThatThrownBy(() -> resolver(ignored -> body.getBytes(StandardCharsets.UTF_8)).resolve(CLIENT_ID))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(INVALID_METADATA));
    }

    private McpClientMetadataResolver resolver(CimdDocumentFetcher fetcher) {
        return new McpClientMetadataResolver(clients, fetcher, new ObjectMapper());
    }

    private static byte[] validMetadata(String clientId) {
        return validString().replace(CLIENT_ID, clientId).getBytes(StandardCharsets.UTF_8);
    }

    private static String baseMetadata(String extra) {
        return "{" + extra
                + "\"client_id\":\"" + CLIENT_ID + "\","
                + "\"client_name\":\"Client\","
                + "\"redirect_uris\":[\"https://client.example/callback\"],"
                + "\"token_endpoint_auth_method\":\"none\","
                + "\"grant_types\":[\"authorization_code\",\"refresh_token\"]}";
    }

    private static String validString() {
        return baseMetadata("");
    }
}
