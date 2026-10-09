package fruition.access.security.oauth.handler;

import fruition.access.security.oauth.OAuthExchangeCodeStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock OAuthExchangeCodeStore exchangeCodeStore;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock HttpSession session;
    @Mock Authentication authentication;

    @Test
    void success_issuesCodeAndInvalidatesHandshakeSession() throws Exception {
        when(authentication.getName()).thenReturn("user_1f9a74af");
        when(exchangeCodeStore.issue("user_1f9a74af", null)).thenReturn("exchange-code");
        when(request.getSession(false)).thenReturn(session);
        var handler = new OAuth2AuthenticationSuccessHandler(
                exchangeCodeStore, "http://localhost:3000/oauth/callback", "fruition://oauth/callback");

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(session).invalidate();
        verify(response).sendRedirect("http://localhost:3000/oauth/callback?code=exchange-code");
    }

    /** 콜백에서 세션의 인가 요청을 꺼낸 상태의 요청. linkUserId가 있으면 연동 모드다. */
    static org.springframework.mock.web.MockHttpServletRequest linkCallback(String linkUserId) {
        return callbackWithAttribute("link_user_id", linkUserId);
    }

    /** 데스크톱 로그인(PKCE challenge가 묶인 인가 요청)의 콜백. */
    static org.springframework.mock.web.MockHttpServletRequest desktopCallback(String codeChallenge) {
        return callbackWithAttribute("desktop_code_challenge", codeChallenge);
    }

    private static org.springframework.mock.web.MockHttpServletRequest callbackWithAttribute(String name, String value) {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var repository = fruition.access.security.oauth.OAuthLinkFlow.repository();
        repository.saveAuthorizationRequest(
                org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://accounts.example/auth").clientId("id").state("s")
                        .attributes(a -> a.put(name, value)).build(),
                request, new org.springframework.mock.web.MockHttpServletResponse());
        request.setParameter("state", "s");
        repository.removeAuthorizationRequest(request, new org.springframework.mock.web.MockHttpServletResponse());
        return request;
    }

    @Test
    void linkSuccess_issuesLinkCodeInsteadOfLoginCode() throws Exception {
        var principal = new org.springframework.security.oauth2.core.user.DefaultOAuth2User(java.util.List.of(),
                java.util.Map.of("internal_user_id", "user_local", "link_provider_user_id", "google-sub-1"),
                "internal_user_id");
        var linkAuthentication = new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                principal, java.util.List.of(), "google");
        when(exchangeCodeStore.issueLinkCode(
                new OAuthExchangeCodeStore.PendingLink("user_local", "google", "google-sub-1"))).thenReturn("link-code");
        var handler = new OAuth2AuthenticationSuccessHandler(
                exchangeCodeStore, "http://localhost:3000/oauth/callback", "fruition://oauth/callback");

        handler.onAuthenticationSuccess(linkCallback("user_local"), response, linkAuthentication);

        verify(exchangeCodeStore, org.mockito.Mockito.never()).issue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(response).sendRedirect("http://localhost:3000/oauth/callback?link_code=link-code");
    }

    @Test
    void newSocialUser_redirectsWithSignupTokenInsteadOfLoginCode() throws Exception {
        var principal = new org.springframework.security.oauth2.core.user.DefaultOAuth2User(java.util.List.of(),
                java.util.Map.of("internal_user_id", "signup-token", "signup_token", "signup-token"),
                "internal_user_id");
        var signupAuthentication = new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                principal, java.util.List.of(), "google");
        var handler = new OAuth2AuthenticationSuccessHandler(
                exchangeCodeStore, "http://localhost:3000/oauth/callback", "fruition://oauth/callback");

        handler.onAuthenticationSuccess(new org.springframework.mock.web.MockHttpServletRequest(), response,
                signupAuthentication);

        verify(exchangeCodeStore, org.mockito.Mockito.never()).issue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(response).sendRedirect("http://localhost:3000/oauth/callback?signup_token=signup-token");
    }

    @Test
    void desktopSuccess_redirectsToDeepLinkWithCodeBoundToChallenge() throws Exception {
        when(authentication.getName()).thenReturn("user_1f9a74af");
        when(exchangeCodeStore.issue("user_1f9a74af", "challenge")).thenReturn("exchange-code");
        var handler = new OAuth2AuthenticationSuccessHandler(
                exchangeCodeStore, "http://localhost:3000/oauth/callback", "fruition://oauth/callback");

        handler.onAuthenticationSuccess(desktopCallback("challenge"), response, authentication);

        verify(response).sendRedirect("fruition://oauth/callback?code=exchange-code");
    }

    @Test
    void desktopNewSocialUser_redirectsToDeepLinkWithSignupToken() throws Exception {
        var principal = new org.springframework.security.oauth2.core.user.DefaultOAuth2User(java.util.List.of(),
                java.util.Map.of("internal_user_id", "signup-token", "signup_token", "signup-token"),
                "internal_user_id");
        var handler = new OAuth2AuthenticationSuccessHandler(
                exchangeCodeStore, "http://localhost:3000/oauth/callback", "fruition://oauth/callback");

        handler.onAuthenticationSuccess(desktopCallback("challenge"), response,
                new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                        principal, java.util.List.of(), "google"));

        verify(response).sendRedirect("fruition://oauth/callback?signup_token=signup-token");
    }
}
