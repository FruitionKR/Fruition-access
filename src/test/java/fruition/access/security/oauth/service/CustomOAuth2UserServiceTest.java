package fruition.access.security.oauth.service;

import fruition.access.security.oauth.OAuthLinkFlow;
import fruition.access.user.service.OAuthUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class CustomOAuth2UserServiceTest {

    private final OAuthUserService oAuthUserService = mock(OAuthUserService.class);
    private final CustomOAuth2UserService service = spy(new CustomOAuth2UserService(oAuthUserService));
    private final OAuth2UserRequest userRequest = new OAuth2UserRequest(
            ClientRegistration.withRegistrationId("google")
                    .clientId("id").clientSecret("secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("https://accounts.example/auth").tokenUri("https://accounts.example/token")
                    .userInfoUri("https://accounts.example/userinfo").userNameAttributeName("sub")
                    .build(),
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "t", Instant.now(), Instant.now().plusSeconds(60)));

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 콜백 요청에서 세션의 인가 요청이 꺼내진 상태를 만든다. linkUserId가 null이면 일반 로그인. */
    private void callbackWithLinkTarget(String linkUserId) {
        var request = new MockHttpServletRequest();
        var builder = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.example/auth").clientId("id").state("s");
        if (linkUserId != null) builder.attributes(a -> a.put("link_user_id", linkUserId));
        var repository = OAuthLinkFlow.repository();
        repository.saveAuthorizationRequest(builder.build(), request, new MockHttpServletResponse());
        request.setParameter("state", "s");
        repository.removeAuthorizationRequest(request, new MockHttpServletResponse());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        doReturn(new DefaultOAuth2User(List.of(), Map.of("sub", "google-sub-1", "email", "a@pusan.ac.kr"), "sub"))
                .when(service).loadProviderUser(any());
    }

    @Test
    void linkMode_passesProviderUserIdOnWithoutTouchingAccounts() {
        callbackWithLinkTarget("user_local");

        var user = service.loadUser(userRequest);

        assertThat(user.getName()).isEqualTo("user_local");
        assertThat((String) user.getAttribute(CustomOAuth2UserService.PROVIDER_USER_ID_ATTRIBUTE))
                .isEqualTo("google-sub-1");
        org.mockito.Mockito.verifyNoInteractions(oAuthUserService);
    }

    @Test
    void linkModeWithoutLogin_failsWithoutTouchingAccounts() {
        callbackWithLinkTarget("");

        assertThatThrownBy(() -> service.loadUser(userRequest))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class, e ->
                        assertThat(e.getError().getErrorCode()).isEqualTo(CustomOAuth2UserService.LINK_FAILED));
        verify(service, never()).loadProviderUser(any());
    }

    @Test
    void newSocialAccount_getsSignupTokenInsteadOfAccount() {
        callbackWithLinkTarget(null);
        org.mockito.Mockito.when(oAuthUserService.findUser(org.mockito.ArgumentMatchers.eq("google"), any()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(oAuthUserService.startSignup(org.mockito.ArgumentMatchers.eq("google"), any(), any()))
                .thenReturn("signup-token");

        var user = service.loadUser(userRequest);

        assertThat((String) user.getAttribute(CustomOAuth2UserService.SIGNUP_TOKEN_ATTRIBUTE)).isEqualTo("signup-token");
        assertThat(user.getName()).isEqualTo("signup-token");
    }
}
