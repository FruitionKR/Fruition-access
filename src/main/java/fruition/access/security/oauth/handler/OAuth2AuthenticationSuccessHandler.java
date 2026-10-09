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
import org.springframework.security.oauth2.core.user.OAuth2User;
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
        // 신규 가입이면 동의 화면에서 쓸 가입 대기 토큰을 넘긴다. 계정은 동의를 받은 뒤에 만든다.
        boolean link = OAuthLinkFlow.linkUserId(request) != null;
        String signupToken = signupToken(authentication);
        String param;
        String code;
        if (link) {
            param = "link_code";
            code = exchangeCodeStore.issueLinkCode(pendingLink(userId, authentication));
        } else if (signupToken != null) {
            param = "signup_token";
            code = signupToken;
        } else {
            param = "code";
            code = exchangeCodeStore.issue(userId);
        }
        log.info("[OAuth 인증 성공] userId={} link={} signup={} redirectUri={}",
                signupToken != null ? "-" : userId, link, signupToken != null, frontendRedirectUri);

        // OAuth handshake에만 필요한 세션 인증이 이후 JWT API 요청에 섞이면
        // @AuthenticationPrincipal이 OAuth2User를 String으로 해석하지 못해 null이 된다.
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam(param, code)
                .build()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }

    private static String signupToken(Authentication authentication) {
        return authentication.getPrincipal() instanceof OAuth2User user
                ? user.getAttribute(CustomOAuth2UserService.SIGNUP_TOKEN_ATTRIBUTE) : null;
    }

    private static PendingLink pendingLink(String userId, Authentication authentication) {
        var token = (OAuth2AuthenticationToken) authentication;
        return new PendingLink(userId, token.getAuthorizedClientRegistrationId(),
                token.getPrincipal().getAttribute(CustomOAuth2UserService.PROVIDER_USER_ID_ATTRIBUTE));
    }
}
