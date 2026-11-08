package fruition.access.mcp;

import fruition.access.mcp.dto.McpAuthorizationRequest;
import fruition.access.mcp.dto.McpAuthorizationResponse;
import fruition.access.mcp.repository.McpClientPolicyRepository;
import fruition.access.mcp.repository.McpGrantRepository;
import fruition.access.mcp.repository.McpWorkspacePolicyRepository;
import fruition.access.mcp.service.McpAuthorizationService;
import fruition.access.mcp.service.McpCurrentAuthorizationReader;
import fruition.access.security.SecurityConfig;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import fruition.access.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import fruition.shared.security.JwtAuthenticationFilter;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import fruition.access.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpAuthorizationServiceTest {
    @Test
    void repositoryFailureReturnsRedactedUnavailableDecision() {
        McpCurrentAuthorizationReader reader = mock(McpCurrentAuthorizationReader.class);
        McpAuthorizationService service = new McpAuthorizationService(reader,
                mock(McpGrantRepository.class), mock(McpWorkspacePolicyRepository.class),
                mock(McpClientPolicyRepository.class), mock(UserRepository.class), mock(WorkspaceRepository.class),
                mock(WorkspaceMemberRepository.class));
        UUID grantId = UUID.randomUUID();
        McpAuthorizationRequest request = mock(McpAuthorizationRequest.class);
        when(reader.authorize(grantId, request)).thenThrow(new IllegalStateException("database unavailable"));

        McpAuthorizationResponse response = service.authorize(grantId, request);

        assertThat(response.decision()).isEqualTo(McpAuthorizationResponse.Decision.DENY);
        assertThat(response.denyReason()).isEqualTo(McpAuthorizationResponse.DenyReason.AUTHZ_UNAVAILABLE);
        assertThat(response.grant()).isNull();
        assertThat(response.policy()).isNull();
        assertThat(response.currentAccess()).isNull();
        assertThat(response.effectiveScopes()).isEmpty();
    }

    @Test
    void blankInternalTokenFailsNonProductionContextCreation() {
        for (String token : new String[] {"", "   "}) {
            new WebApplicationContextRunner()
                    .withUserConfiguration(SecurityConfig.class)
                    .withBean(JwtAuthenticationFilter.class, () -> mock(JwtAuthenticationFilter.class))
                    .withBean(CustomOAuth2UserService.class, () -> mock(CustomOAuth2UserService.class))
                    .withBean(OAuth2AuthenticationSuccessHandler.class,
                            () -> mock(OAuth2AuthenticationSuccessHandler.class))
                    .withBean(OAuth2AuthenticationFailureHandler.class,
                            () -> mock(OAuth2AuthenticationFailureHandler.class))
                    .withBean(ClientRegistrationRepository.class,
                            () -> mock(ClientRegistrationRepository.class))
                    .withBean(OAuthExchangeCodeStore.class, () -> mock(OAuthExchangeCodeStore.class))
                    .withPropertyValues("app.cors.allowed-origins=http://localhost",
                            "app.internal.callback-token=" + token)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseMessage("app.internal.callback-token must not be blank");
                    });
        }
    }
}
