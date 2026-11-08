package fruition.access.mcp.oauth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mcp.oauth.enabled", havingValue = "true")
public final class McpRegisteredClientRepository implements RegisteredClientRepository {
    private final McpClientMetadataResolver clients;
    private final McpOAuthSettings settings;

    public McpRegisteredClientRepository(McpClientMetadataResolver clients, McpOAuthSettings settings) {
        this.clients = clients;
        this.settings = settings;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Dynamic client registration is disabled");
    }

    @Override
    public RegisteredClient findById(String id) {
        return findByClientId(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        McpClientMetadata metadata = clients.resolve(clientId);
        RegisteredClient.Builder builder = RegisteredClient.withId(metadata.clientId())
                .clientId(metadata.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope("read").scope("write_propose").scope("wiki_write")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(settings.accessTokenTtl())
                        .refreshTokenTimeToLive(settings.refreshTokenTtl())
                        .reuseRefreshTokens(false)
                        .build());
        metadata.redirectUris().forEach(builder::redirectUri);
        return builder.build();
    }
}
