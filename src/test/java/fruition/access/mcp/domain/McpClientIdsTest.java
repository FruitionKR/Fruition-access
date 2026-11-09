package fruition.access.mcp.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientIdsTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://client.example", "https://client.example/", "https://client.example/./metadata",
            "https://client.example/a/../metadata", "https://client.example/a/./metadata"
    })
    void rejectsClientIdentifiersWithoutAMetadataPathOrWithLiteralDotSegments(String clientId) {
        assertThat(McpClientIds.isValid(clientId)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://client.example/metadata", "https://client.example/metadata?version=1",
            "https://client.example/a//metadata", "https://client.example/%2e/metadata"
    })
    void preservesValidRawPathsAndQueryStringsWithoutNormalization(String clientId) {
        assertThat(McpClientIds.requireValid(clientId)).isEqualTo(clientId);
    }
}
