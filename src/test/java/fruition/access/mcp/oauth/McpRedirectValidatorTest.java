package fruition.access.mcp.oauth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static fruition.access.mcp.oauth.McpClientMetadataException.Reason.REDIRECT_NOT_REGISTERED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRedirectValidatorTest {
    private final McpRedirectValidator validator = new McpRedirectValidator();

    @Test
    void normalHttpsRedirectRequiresExactStringMatch() {
        McpClientMetadata client = client("https://claude.ai/api/mcp/auth_callback");

        ValidatedMcpRedirect result = validator.validate(client, "https://claude.ai/api/mcp/auth_callback");

        assertThat(result).isEqualTo(new ValidatedMcpRedirect(
                "https://claude.ai/api/mcp/auth_callback", "claude.ai", false));
        assertDenied(client, "https://claude.ai:443/api/mcp/auth_callback");
        assertDenied(client, "https://CLAUDE.ai/api/mcp/auth_callback");
        assertDenied(client, "http://claude.ai/api/mcp/auth_callback");
    }

    @Test
    void localhostLoopbackChangesOnlyThePortAndRetainsTheActualRequest() {
        McpClientMetadata client = client("http://localhost:41000/callback?return=%2Fhome");

        ValidatedMcpRedirect result = validator.validate(client,
                "http://localhost:53123/callback?return=%2Fhome");

        assertThat(result.uri()).isEqualTo("http://localhost:53123/callback?return=%2Fhome");
        assertThat(result.loopbackPortException()).isTrue();
        assertDenied(client, "http://localhost:53123/callback?return=/home");
        assertDenied(client, "http://localhost:53123/other?return=%2Fhome");
        assertDenied(client, "https://localhost:53123/callback?return=%2Fhome");
        assertDenied(client, "http://127.0.0.1:53123/callback?return=%2Fhome");
    }

    @Test
    void ipv4LoopbackMayVaryOnlyThePort() {
        McpClientMetadata client = client("http://127.0.0.1/callback");

        assertThat(validator.validate(client, "http://127.0.0.1:49152/callback").uri())
                .isEqualTo("http://127.0.0.1:49152/callback");
        assertDenied(client, "http://[::1]:49152/callback");
    }

    private void assertDenied(McpClientMetadata client, String requested) {
        assertThatThrownBy(() -> validator.validate(client, requested))
                .isInstanceOfSatisfying(McpClientMetadataException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(REDIRECT_NOT_REGISTERED));
    }

    private static McpClientMetadata client(String redirect) {
        return new McpClientMetadata("https://client.example/metadata", Optional.of("Client"),
                List.of(redirect), Set.of(), "none", Set.of("authorization_code", "refresh_token"));
    }
}
