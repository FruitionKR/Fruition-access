package fruition.access.security.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OAuthLinkFlowTest {

    private final OAuthExchangeCodeStore store = mock(OAuthExchangeCodeStore.class);
    private final InMemoryClientRegistrationRepository clients = new InMemoryClientRegistrationRepository(
            ClientRegistration.withRegistrationId("google")
                    .clientId("id").clientSecret("secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("https://accounts.example/auth").tokenUri("https://accounts.example/token")
                    .build());

    /** 인가 시작 → 세션 저장 → 콜백에서 꺼내기까지 거친 뒤 콜백 요청에서 읽은 연동 대상. */
    private String linkUserIdAfterRoundTrip(MockHttpServletRequest start) {
        var authorizationRequest = OAuthLinkFlow.resolver(clients, store).resolve(start);
        var repository = OAuthLinkFlow.repository();
        repository.saveAuthorizationRequest(authorizationRequest, start, new MockHttpServletResponse());

        var callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setSession(start.getSession());
        callback.setParameter("state", authorizationRequest.getState());
        repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());
        return OAuthLinkFlow.linkUserId(callback);
    }

    private static MockHttpServletRequest start(String mode, String linkToken) {
        var request = new MockHttpServletRequest("GET", "/oauth2/authorization/google");
        request.setServletPath("/oauth2/authorization/google");
        if (mode != null) request.setParameter("mode", mode);
        if (linkToken != null) request.setParameter("link_token", linkToken);
        return request;
    }

    @Test
    void linkModeWithValidToken_bindsLinkTargetToAuthorizationRequest() {
        when(store.consumeLinkToken("token", "google")).thenReturn(Optional.of("user_local"));

        assertThat(linkUserIdAfterRoundTrip(start("link", "token")))
                .isEqualTo("user_local");
    }

    @Test
    void linkModeWithoutLogin_isMarkedAsFailedLink() {
        assertThat(linkUserIdAfterRoundTrip(start("link", null))).isEmpty();
    }

    @Test
    void tokenWithoutLinkMode_staysNormalLogin() {
        assertThat(linkUserIdAfterRoundTrip(start(null, "token"))).isNull();
        verifyNoInteractions(store);
    }
}
