package fruition.access.mcp.oauth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpOAuthBrowserAuthenticationEntryPointTest {
    @Test
    void configurationAcceptsOnlyFixedHttpsOrLoopbackDevelopmentUri() {
        for (String uri : new String[]{"https://app.example.com/mcp/authorize",
                "http://localhost:3000/mcp/authorize", "http://127.0.0.1:3000/mcp/authorize",
                "http://[::1]:3000/mcp/authorize"}) {
            assertThat(new McpOAuthBrowserAuthenticationEntryPoint(uri)).isNotNull();
        }
        for (String uri : new String[]{"", "not a uri", "http://app.example.com/mcp/authorize",
                "http://192.168.1.2/mcp/authorize", "https://app.example.com/other",
                "https://user@app.example.com/mcp/authorize", "https://app.example.com/mcp/authorize?next=/",
                "https://app.example.com/mcp/authorize#fragment",
                "https://" + "a".repeat(McpOAuthBrowserAuthenticationEntryPoint.MAX_LOCATION_BYTES)
                        + "/mcp/authorize"}) {
            assertThatIllegalArgumentException()
                    .as(uri)
                    .isThrownBy(() -> new McpOAuthBrowserAuthenticationEntryPoint(uri));
        }
    }

    @Test
    void handoffRejectsMalformedEncodingDuplicatesAndTechnicalOverflowAtExactBounds() {
        var entryPoint = new McpOAuthBrowserAuthenticationEntryPoint("https://app.example.com/mcp/authorize");
        for (String query : new String[]{"state=%", "state=%GG", "state=%C3%28",
                "state=one&%73tate=two", "next=%2F%2Fevil.test", "state=" + "x".repeat(1025),
                "redirect_uri=" + "x".repeat(McpOAuthBrowserAuthenticationEntryPoint.MAX_RAW_QUERY_BYTES),
                "redirect_uri=" + "!".repeat(2700)}) {
            assertThatIllegalArgumentException().as(query)
                    .isThrownBy(() -> entryPoint.handoffLocation(query));
        }

        String locationPrefix = entryPoint.handoffLocation("redirect_uri=");
        String locationAtLimit = "redirect_uri=" + "a".repeat(
                McpOAuthBrowserAuthenticationEntryPoint.MAX_LOCATION_BYTES
                        - locationPrefix.getBytes(StandardCharsets.UTF_8).length);
        assertThat(entryPoint.handoffLocation(locationAtLimit).getBytes(StandardCharsets.UTF_8))
                .hasSize(McpOAuthBrowserAuthenticationEntryPoint.MAX_LOCATION_BYTES);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> entryPoint.handoffLocation(locationAtLimit + "a"));

        String rawSuffix = "aa";
        String rawAtLimit = "scope=" + "%41".repeat(
                (McpOAuthBrowserAuthenticationEntryPoint.MAX_RAW_QUERY_BYTES
                        - "scope=".length() - rawSuffix.length()) / 3) + rawSuffix;
        assertThat(rawAtLimit.getBytes(StandardCharsets.UTF_8))
                .hasSize(McpOAuthBrowserAuthenticationEntryPoint.MAX_RAW_QUERY_BYTES);
        assertThat(entryPoint.handoffLocation(rawAtLimit)).isNotBlank();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> entryPoint.handoffLocation(rawAtLimit + "a"));
    }
}
