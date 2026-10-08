package fruition.access.security.oauth.handler;

import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.OAuthExchangeCodeStore.PendingLink;
import fruition.access.security.oauth.OAuthLinkFlow;
import fruition.access.security.oauth.service.CustomOAuth2UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationSuccessHandler.class);

    private final OAuthExchangeCodeStore exchangeCodeStore;
    private final String frontendRedirectUri;

    public OAuth2AuthenticationSuccessHandler(OAuthExchangeCodeStore exchangeCodeStore,
                                              @Value("${app.oauth.frontend-redirect-uri}") String frontendRedirectUri) {
        this.exchangeCodeStore = exchangeCodeStore;
        this.frontendRedirectUri = frontendRedirectUri;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        String userId = authentication.getName();
        // 연동이면 로그인 code 대신, 로그인한 프론트가 확정 API에 낼 연동 code를 발급한다.
        boolean link = OAuthLinkFlow.linkUserId(request) != null;
        String code = link ? exchangeCodeStore.issueLinkCode(pendingLink(userId, authentication))
                : exchangeCodeStore.issue(userId);
        log.info("[OAuth 인증 성공] userId={} link={} redirectUri={}", userId, link, frontendRedirectUri);

        // OAuth handshake에만 필요한 세션 인증이 이후 JWT API 요청에 섞이면
        // @AuthenticationPrincipal이 OAuth2User를 String으로 해석하지 못해 null이 된다.
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam(link ? "link_code" : "code", code)
                .build()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }

    private static PendingLink pendingLink(String userId, Authentication authentication) {
        var token = (OAuth2AuthenticationToken) authentication;
        return new PendingLink(userId, token.getAuthorizedClientRegistrationId(),
                token.getPrincipal().getAttribute(CustomOAuth2UserService.PROVIDER_USER_ID_ATTRIBUTE));
    }
}
