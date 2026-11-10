package fruition.access.mcp.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class McpOAuthGrantAuthenticationProvider {
    private McpOAuthGrantAuthenticationProvider() {}

    static final class AuthorizationCode implements AuthenticationProvider {
        private final McpOAuthCredentialService credentials;

        AuthorizationCode(McpOAuthCredentialService credentials) { this.credentials = credentials; }

        @Override
        public Authentication authenticate(Authentication authentication) {
            OAuth2AuthorizationCodeAuthenticationToken request =
                    (OAuth2AuthorizationCodeAuthenticationToken) authentication;
            OAuth2ClientAuthenticationToken client = client(request.getPrincipal());
            Optional<McpOAuthCredentialService.IssuedTokens> issued = credentials.exchangeCode(
                    request.getCode(), (String) client.getPrincipal(), request.getRedirectUri(),
                    optional(request.getAdditionalParameters(), "resource"),
                    exact(request.getAdditionalParameters(), PkceParameterNames.CODE_VERIFIER));
            return issued.<Authentication>map(tokens -> response(client, tokens)).orElseThrow(
                    McpOAuthGrantAuthenticationProvider::invalidGrant);
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2AuthorizationCodeAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    static final class Refresh implements AuthenticationProvider {
        private final McpOAuthCredentialService credentials;

        Refresh(McpOAuthCredentialService credentials) { this.credentials = credentials; }

        @Override
        public Authentication authenticate(Authentication authentication) {
            OAuth2RefreshTokenAuthenticationToken request = (OAuth2RefreshTokenAuthenticationToken) authentication;
            OAuth2ClientAuthenticationToken client = client(request.getPrincipal());
            McpOAuthCredentialService.RefreshResult result = credentials.refresh(request.getRefreshToken(),
                    (String) client.getPrincipal(), optional(request.getAdditionalParameters(), "resource"),
                    request.getScopes());
            if (result.status() != McpOAuthCredentialService.RefreshResult.Status.SUCCESS) throw invalidGrant();
            return response(client, result.tokens());
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }

    private static OAuth2AccessTokenAuthenticationToken response(OAuth2ClientAuthenticationToken client,
                                                                  McpOAuthCredentialService.IssuedTokens tokens) {
        RegisteredClient registered = client.getRegisteredClient();
        OAuth2AccessToken access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                tokens.accessToken(), tokens.accessIssuedAt(), tokens.accessExpiresAt(), tokens.scopes());
        OAuth2RefreshToken refresh = new OAuth2RefreshToken(tokens.refreshToken(), tokens.accessIssuedAt(),
                tokens.refreshExpiresAt());
        return new OAuth2AccessTokenAuthenticationToken(registered, client, access, refresh);
    }

    private static OAuth2ClientAuthenticationToken client(Object principal) {
        if (principal instanceof OAuth2ClientAuthenticationToken client && client.isAuthenticated()
                && client.getRegisteredClient() != null) return client;
        throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    private static String exact(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value instanceof String text && !text.isBlank()) return text;
        throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST));
    }

    /** Absent means "use the resource bound at authorization"; present must be a single non-blank value. */
    private static String optional(Map<String, Object> values, String name) {
        return values.containsKey(name) ? exact(values, name) : null;
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT));
    }
}
