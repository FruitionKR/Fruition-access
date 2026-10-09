package fruition.access.security.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        return OAuthLinkFlow.linkUserId(callbackAfterRoundTrip(start));
    }

    private MockHttpServletRequest callbackAfterRoundTrip(MockHttpServletRequest start) {
        var authorizationRequest = OAuthLinkFlow.resolver(clients, store).resolve(start);
        var repository = OAuthLinkFlow.repository();
        repository.saveAuthorizationRequest(authorizationRequest, start, new MockHttpServletResponse());

        var callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        callback.setSession(start.getSession());
        callback.setParameter("state", authorizationRequest.getState());
        repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());
        return callback;
    }

    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static MockHttpServletRequest desktopStart(String challenge, String method) {
        var request = start(null, null);
        request.setParameter("client", "desktop");
        if (challenge != null) request.setParameter("code_challenge", challenge);
        if (method != null) request.setParameter("code_challenge_method", method);
        return request;
    }

    @Test
    void desktopStart_bindsCodeChallengeToAuthorizationRequest() {
        var callback = callbackAfterRoundTrip(desktopStart(CHALLENGE, "S256"));

        assertThat(OAuthLinkFlow.desktopCodeChallenge(callback)).isEqualTo(CHALLENGE);
        assertThat(OAuthLinkFlow.linkUserId(callback)).isNull();
    }

    @Test
    void webStart_hasNoCodeChallenge() {
        assertThat(OAuthLinkFlow.desktopCodeChallenge(callbackAfterRoundTrip(start(null, null)))).isNull();
    }

    @Test
    void desktopStart_withoutValidS256Challenge_isRejected() {
        var resolver = OAuthLinkFlow.resolver(clients, store);

        for (var request : java.util.List.of(desktopStart(CHALLENGE, "plain"), desktopStart(CHALLENGE, null),
                desktopStart(null, "S256"), desktopStart("too-short", "S256"), desktopStart(CHALLENGE + "+/", "S256"))) {
            assertThatThrownBy(() -> resolver.resolve(request))
                    .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthorizationException.class);
        }
    }

    @Test
    void desktopStart_inLinkMode_isRejectedWithoutConsumingLinkToken() {
        var request = desktopStart(CHALLENGE, "S256");
        request.setParameter("mode", "link");
        request.setParameter("link_token", "token");

        assertThatThrownBy(() -> OAuthLinkFlow.resolver(clients, store).resolve(request))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthorizationException.class);
        verifyNoInteractions(store);
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
