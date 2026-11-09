package fruition.access.mcp.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import fruition.access.mcp.repository.McpClientPolicyRepository;
import org.apache.hc.core5.ssl.SSLContexts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.mcp.oauth.client-metadata", name = {"max-response-bytes", "timeout"})
@EnableConfigurationProperties(McpClientMetadataSettings.class)
class McpClientMetadataConfiguration {
    @Bean
    CimdDocumentFetcher cimdDocumentFetcher(McpClientMetadataSettings settings) {
        return new SecureCimdHttpClient(settings, new PublicAddressDnsResolver(), SSLContexts.createSystemDefault());
    }

    @Bean
    McpClientMetadataResolver mcpClientMetadataResolver(McpClientPolicyRepository clients,
                                                        CimdDocumentFetcher fetcher,
                                                        ObjectMapper objectMapper) {
        return new McpClientMetadataResolver(clients, fetcher, objectMapper);
    }
}
