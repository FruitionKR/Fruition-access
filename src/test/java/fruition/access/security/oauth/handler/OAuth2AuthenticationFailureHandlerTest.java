package fruition.access.security.oauth.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.AuthenticationException;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationFailureHandlerTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock HttpSession session;
    @Mock AuthenticationException exception;

    @Test
    void failure_invalidatesHandshakeSessionAndRedirectsWithError() throws Exception {
        when(request.getSession(false)).thenReturn(session);
        var handler = new OAuth2AuthenticationFailureHandler("http://localhost:3000/oauth/callback", "fruition://oauth/callback");

        handler.onAuthenticationFailure(request, response, exception);

        verify(session).invalidate();
        verify(response).sendRedirect("http://localhost:3000/oauth/callback?error=oauth_failed");
    }

    @Test
    void linkFailure_redirectsWithLinkFailedInsteadOfLoginError() throws Exception {
        var handler = new OAuth2AuthenticationFailureHandler("http://localhost:3000/oauth/callback", "fruition://oauth/callback");
        var denied = new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                new org.springframework.security.oauth2.core.OAuth2Error("access_denied"));

        handler.onAuthenticationFailure(OAuth2AuthenticationSuccessHandlerTest.linkCallback("user_local"), response, denied);

        verify(response).sendRedirect("http://localhost:3000/oauth/callback?link=failed");
    }

    @Test
    void desktopFailure_redirectsToDeepLink() throws Exception {
        var handler = new OAuth2AuthenticationFailureHandler("http://localhost:3000/oauth/callback", "fruition://oauth/callback");

        handler.onAuthenticationFailure(OAuth2AuthenticationSuccessHandlerTest.desktopCallback("challenge"), response, exception);

        verify(response).sendRedirect("fruition://oauth/callback?error=oauth_failed");
    }
}
