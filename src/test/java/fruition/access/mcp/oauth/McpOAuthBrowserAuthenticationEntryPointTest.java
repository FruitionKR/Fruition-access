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
    void handoffDropsUnknownParametersButKeepsAllowedOnes() {
        var entryPoint = new McpOAuthBrowserAuthenticationEntryPoint("https://app.example.com/mcp/authorize");
        String location = entryPoint.handoffLocation("response_type=code&prompt=consent&login_hint=a%40b.test"
                + "&nonce=n-1&access_type=offline&client_id=https%3A%2F%2Fclient.test%2Fm&next=%2F%2Fevil.test"
                + "&state=s1&prompt=login");
        assertThat(location).isEqualTo("https://app.example.com/mcp/authorize?response_type=code"
                + "&client_id=https%3A%2F%2Fclient.test%2Fm&state=s1");
        assertThat(entryPoint.handoffLocation("prompt=consent"))
                .isEqualTo("https://app.example.com/mcp/authorize");
    }

    @Test
    void handoffRejectsMalformedEncodingDuplicatesAndTechnicalOverflowAtExactBounds() {
        var entryPoint = new McpOAuthBrowserAuthenticationEntryPoint("https://app.example.com/mcp/authorize");
        for (String query : new String[]{"state=%", "state=%GG", "state=%C3%28",
                "state=one&%73tate=two", "scope=read&scope=read", "state=" + "x".repeat(1025),
                "request=eyJhbGciOiJub25lIn0", "request_uri=urn%3Aexample%3Arequest",
                "state=s&request_uri=https%3A%2F%2Fevil.test%2Fjar", "next=%ZZ",
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
