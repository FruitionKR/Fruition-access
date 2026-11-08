package fruition.access.mcp.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.shared.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mcp.oauth.enabled", havingValue = "true")
public class McpAuthorizationServerConfiguration {
    @Bean
    McpOAuthSettings mcpOAuthSettings(@Value("${app.mcp.oauth.issuer}") URI issuer,
                                      @Value("${app.mcp.oauth.resource}") URI resource,
                                      @Value("${app.mcp.oauth.authorization-code-ttl}") Duration codeTtl,
                                      @Value("${app.mcp.oauth.access-token-ttl}") Duration accessTtl,
                                      @Value("${app.mcp.oauth.refresh-token-ttl}") Duration refreshTtl,
                                      @Value("${app.mcp.oauth.pending-authorization-ttl}") Duration pendingTtl) {
        return new McpOAuthSettings(issuer, resource, codeTtl, accessTtl, refreshTtl, pendingTtl);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(McpOAuthSettings settings) {
        return AuthorizationServerSettings.builder()
                .issuer(settings.issuer().toString())
                // The backend consent contract owns /oauth2/authorize; keep SAS's unused browser filter unreachable.
                .authorizationEndpoint("/oauth2/_sas-internal-disabled")
                .tokenEndpoint("/oauth2/token")
                .jwkSetEndpoint("/oauth2/jwks")
                .build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(JwtTokenProvider jwt) {
        RSAKey key = new RSAKey.Builder(jwt.rsaVerificationKey()).privateKey(jwt.rsaSigningKey())
                .keyID(jwt.rsaKeyId()).build();
        return new ImmutableJWKSet<>(new JWKSet(key));
    }

    @Bean
    OAuth2AuthorizationService disabledSasAuthorizationService() {
        return new OAuth2AuthorizationService() {
            @Override public void save(OAuth2Authorization authorization) {
                throw new UnsupportedOperationException("Generic SAS authorization storage is disabled");
            }
            @Override public void remove(OAuth2Authorization authorization) {}
            @Override public OAuth2Authorization findById(String id) { return null; }
            @Override public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) { return null; }
        };
    }

    @Bean
    InMemoryOAuth2AuthorizationConsentService sasConsentService() {
        // SAS requires the service, while MCP consent is persisted in access-owned pending/grant tables.
        return new InMemoryOAuth2AuthorizationConsentService();
    }

    @Bean
    McpOAuthBrowserAuthenticationEntryPoint mcpOAuthBrowserAuthenticationEntryPoint(
            @Value("${app.mcp.oauth.frontend-authorization-uri}") String frontendAuthorizationUri) {
        return new McpOAuthBrowserAuthenticationEntryPoint(frontendAuthorizationUri);
    }

    @Bean
    @Order(1)
    SecurityFilterChain mcpAuthorizationServerChain(HttpSecurity http,
                                                    McpOAuthSettings settings,
                                                    RegisteredClientRepository registeredClients,
                                                    OAuth2AuthorizationService disabledSasAuthorizationService,
                                                    InMemoryOAuth2AuthorizationConsentService sasConsentService,
                                                    McpRegisteredClientRepository clients,
                                                    McpOAuthCredentialService credentials,
                                                    McpOAuthBrowserAuthenticationEntryPoint browserEntryPoint,
                                                    JwtAuthenticationFilter webJwtFilter) throws Exception {
        OAuth2AuthorizationServerConfigurer sas = OAuth2AuthorizationServerConfigurer.authorizationServer();
        var matcher = new OrRequestMatcher(
                new AntPathRequestMatcher("/.well-known/oauth-authorization-server", HttpMethod.GET.name()),
                new AntPathRequestMatcher("/oauth2/jwks", HttpMethod.GET.name()),
                new AntPathRequestMatcher("/oauth2/token", HttpMethod.POST.name()),
                new AntPathRequestMatcher("/oauth2/authorize", HttpMethod.GET.name()),
                new AntPathRequestMatcher("/oauth2/authorize/*", HttpMethod.POST.name()));
        HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
        http.securityMatcher(matcher)
                .with(sas, authorizationServer -> authorizationServer
                .registeredClientRepository(registeredClients)
                .authorizationService(disabledSasAuthorizationService)
                .authorizationConsentService(sasConsentService)
                .authorizationServerSettings(authorizationServerSettings(settings))
                .clientAuthentication(client -> client
                        .authenticationConverters(converters -> converters.add(0,
                                new McpPublicClientAuthentication.Converter()))
                        .authenticationProviders(providers -> providers.add(0,
                                new McpPublicClientAuthentication.Provider(clients))))
                .tokenEndpoint(token -> token.authenticationProviders(providers -> {
                    providers.removeIf(provider -> provider instanceof OAuth2AuthorizationCodeAuthenticationProvider
                            || provider instanceof OAuth2RefreshTokenAuthenticationProvider);
                    providers.add(0, new McpOAuthGrantAuthenticationProvider.Refresh(credentials));
                    providers.add(0, new McpOAuthGrantAuthenticationProvider.AuthorizationCode(credentials));
                }))
                .authorizationServerMetadataEndpoint(metadata -> metadata
                        .authorizationServerMetadataCustomizer(builder -> builder
                                .authorizationEndpoint(settings.issuer() + "/oauth2/authorize")
                                .tokenEndpointAuthenticationMethods(methods -> {
                                    methods.clear();
                                    methods.add(ClientAuthenticationMethod.NONE.getValue());
                                })
                                .codeChallengeMethods(methods -> {
                                    methods.clear();
                                    methods.add("S256");
                                })
                                .grantTypes(grants -> {
                                    grants.clear();
                                    grants.add(AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
                                    grants.add(AuthorizationGrantType.REFRESH_TOKEN.getValue());
                                })
                                .responseTypes(types -> {
                                    types.clear();
                                    types.add("code");
                                })
                                .scopes(scopes -> {
                                    scopes.clear();
                                    scopes.addAll(List.of("read", "write_propose", "wiki_write"));
                                })
                                .claim("client_id_metadata_document_supported", true)
                                .claims(claims -> List.of("registration_endpoint", "revocation_endpoint",
                                                "revocation_endpoint_auth_methods_supported", "introspection_endpoint",
                                                "introspection_endpoint_auth_methods_supported",
                                                "device_authorization_endpoint", "pushed_authorization_request_endpoint",
                                                "dpop_signing_alg_values_supported")
                                        .forEach(claims::remove)))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(config -> config.csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(browserEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/.well-known/oauth-authorization-server", "/oauth2/jwks")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/oauth2/token").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(webJwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
