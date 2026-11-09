package fruition.access.mcp.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.Map;

final class McpPublicClientAuthentication {
    private McpPublicClientAuthentication() {}

    static final class Converter implements AuthenticationConverter {
        @Override
        public Authentication convert(HttpServletRequest request) {
            String grantType = request.getParameter(OAuth2ParameterNames.GRANT_TYPE);
            if (!AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(grantType)
                    && !AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grantType)) return null;
            String[] clientIds = request.getParameterMap().get(OAuth2ParameterNames.CLIENT_ID);
            if (clientIds == null || clientIds.length != 1 || clientIds[0].isBlank()
                    || request.getHeader("Authorization") != null
                    || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
            }
            return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null,
                    Map.of("grant_type", grantType));
        }
    }

    static final class Provider implements AuthenticationProvider {
        private final McpRegisteredClientRepository clients;

        Provider(McpRegisteredClientRepository clients) {
            this.clients = clients;
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            OAuth2ClientAuthenticationToken candidate = (OAuth2ClientAuthenticationToken) authentication;
            if (!ClientAuthenticationMethod.NONE.equals(candidate.getClientAuthenticationMethod())) return null;
            try {
                RegisteredClient client = clients.findByClientId((String) candidate.getPrincipal());
                return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
            } catch (RuntimeException denied) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT));
            }
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
        }
    }
}
