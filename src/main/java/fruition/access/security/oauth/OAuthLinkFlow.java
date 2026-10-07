package fruition.access.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 로그인한 사용자가 소셜 계정을 연동하는 OAuth 흐름.
 *
 * <p>{@code /oauth2/authorization/{provider}?mode=link&link_token=...}로 들어오면 연동 시작 API가 준
 * 1회용 토큰을 소비해 연동 대상 사용자 ID를 인가 요청 속성에 넣는다. 인가 요청은 state로 찾아지므로
 * 연동 대상이 state에 묶인다. 콜백에서는 세션에서 꺼낸 인가 요청을 요청 속성에 남겨 user service와
 * handler가 연동 모드를 알 수 있게 한다.
 *
 * <p>콜백에서 바로 연결하지 않고 프론트가 로그인한 채로 확정하게 하는 이유: 토큰이 URL에 실리므로
 * 공격자가 자기 토큰이 든 링크로 피해자의 소셜 계정을 자기 계정에 붙일 수 있다. 확정 시 토큰 발급자와
 * 확정하는 로그인 사용자가 같아야 하므로 이 공격이 막힌다.
 */
public final class OAuthLinkFlow {

    private static final String AUTHORIZATION_BASE_URI = "/oauth2/authorization";
    private static final String LINK_USER_ID = "link_user_id";
    private static final String REMOVED_REQUEST = OAuthLinkFlow.class.getName() + ".AUTHORIZATION_REQUEST";

    private OAuthLinkFlow() {}

    /**
     * 콜백 처리 중인 요청이 연동 모드이면 연동 대상 사용자 ID, 일반 로그인이면 null.
     * 연동 모드인데 토큰이 없거나 무효였으면 빈 문자열이다.
     */
    public static String linkUserId(HttpServletRequest request) {
        return linkUserId(request.getAttribute(REMOVED_REQUEST));
    }

    /** {@link #linkUserId(HttpServletRequest)}와 같지만 현재 스레드의 요청에서 읽는다. */
    public static String currentLinkUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes == null ? null
                : linkUserId(attributes.getAttribute(REMOVED_REQUEST, RequestAttributes.SCOPE_REQUEST));
    }

    private static String linkUserId(Object removed) {
        return removed instanceof OAuth2AuthorizationRequest authorizationRequest
                ? authorizationRequest.getAttribute(LINK_USER_ID)
                : null;
    }

    public static OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository clients,
                                                              OAuthExchangeCodeStore store) {
        var delegate = new DefaultOAuth2AuthorizationRequestResolver(clients, AUTHORIZATION_BASE_URI);
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return withLinkTarget(request, delegate.resolve(request), store);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                return withLinkTarget(request, delegate.resolve(request, clientRegistrationId), store);
            }
        };
    }

    private static OAuth2AuthorizationRequest withLinkTarget(HttpServletRequest request,
                                                             OAuth2AuthorizationRequest authorizationRequest,
                                                             OAuthExchangeCodeStore store) {
        if (authorizationRequest == null || !"link".equals(request.getParameter("mode"))) {
            return authorizationRequest;
        }
        String provider = authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
        String token = request.getParameter("link_token");
        String userId = token == null ? "" : store.consumeLinkToken(token, provider).orElse("");
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .attributes(attributes -> attributes.put(LINK_USER_ID, userId))
                .build();
    }

    public static AuthorizationRequestRepository<OAuth2AuthorizationRequest> repository() {
        var delegate = new HttpSessionOAuth2AuthorizationRequestRepository();
        return new AuthorizationRequestRepository<>() {
            @Override
            public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
                return delegate.loadAuthorizationRequest(request);
            }

            @Override
            public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                                 HttpServletRequest request, HttpServletResponse response) {
                delegate.saveAuthorizationRequest(authorizationRequest, request, response);
            }

            @Override
            public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                         HttpServletResponse response) {
                OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
                if (removed != null) {
                    request.setAttribute(REMOVED_REQUEST, removed);
                }
                return removed;
            }
        };
    }
}
