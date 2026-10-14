package fruition.access.security.oauth.handler;

import fruition.access.security.oauth.OAuthLinkFlow;
import fruition.access.security.oauth.service.CustomOAuth2UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationFailureHandler.class);

    private final String frontendRedirectUri;
    private final String desktopRedirectUri;

    public OAuth2AuthenticationFailureHandler(@Value("${app.oauth.frontend-redirect-uri}") String frontendRedirectUri,
                                              @Value("${app.oauth.desktop-redirect-uri}") String desktopRedirectUri) {
        this.frontendRedirectUri = frontendRedirectUri;
        this.desktopRedirectUri = desktopRedirectUri;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String redirectUri = OAuthLinkFlow.desktopCodeChallenge(request) != null ? desktopRedirectUri : frontendRedirectUri;
        log.warn("[OAuth 인증 실패] errorType={} redirectUri={}", exception.getClass().getSimpleName(), redirectUri);

        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        // 연동 모드였으면 로그인 실패와 구분해 ?link=failed로 돌려보낸다.
        boolean link = OAuthLinkFlow.linkUserId(request) != null;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectUri);
        if (link) {
            builder.queryParam("link", "failed");
        } else if (registeredProviders(exception) != null) {
            // 같은 이메일의 계정이 있어 신규 가입을 거절했다. 프론트는 기존 계정으로 로그인한 뒤 연동하도록 안내한다.
            builder.queryParam("error", CustomOAuth2UserService.EMAIL_REGISTERED)
                    .queryParam("providers", registeredProviders(exception));
        } else {
            builder.queryParam("error", "oauth_failed");
        }
        response.sendRedirect(builder.build().toUriString());
    }

    /** 신규 가입 거절이면 같은 이메일로 가입한 provider 목록(쉼표 구분), 아니면 null. */
    private static String registeredProviders(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException e
                && CustomOAuth2UserService.EMAIL_REGISTERED.equals(e.getError().getErrorCode())) {
            return e.getError().getDescription();
        }
        return null;
    }
}
