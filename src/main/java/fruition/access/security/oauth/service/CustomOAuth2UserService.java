package fruition.access.security.oauth.service;

import fruition.access.security.oauth.OAuthLinkFlow;
import fruition.access.security.oauth.domain.OAuth2UserInfo;

import fruition.access.user.domain.User;
import fruition.access.user.service.OAuthUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);
    private static final String INTERNAL_USER_ID_ATTRIBUTE = "internal_user_id";
    /** 연동 모드에서 확정 단계로 넘길 provider 사용자 ID. */
    public static final String PROVIDER_USER_ID_ATTRIBUTE = "link_provider_user_id";
    public static final String LINK_FAILED = "link_failed";
    /** 약관 동의 전인 신규 소셜 가입. 값은 가입 대기 토큰이고, 성공 handler가 로그인 code 대신 프론트에 넘긴다. */
    public static final String SIGNUP_TOKEN_ATTRIBUTE = "signup_token";

    private final OAuthUserService oAuthUserService;

    public CustomOAuth2UserService(OAuthUserService oAuthUserService) {
        this.oAuthUserService = oAuthUserService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String linkUserId = OAuthLinkFlow.currentLinkUserId();
        if (linkUserId != null && linkUserId.isEmpty()) {
            // 연동 모드인데 연동 시작 API가 준 토큰이 없거나 무효다. 로그인으로 넘기지 않는다.
            throw new OAuth2AuthenticationException(new OAuth2Error(LINK_FAILED));
        }
        OAuth2User oAuth2User = loadProviderUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        log.info("[OAuth 사용자 정보 요청 성공] provider={}", registrationId);

        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.create(registrationId, oAuth2User.getAttributes());

        Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
        if (linkUserId != null) {
            // 연동은 프론트의 확정 요청에서 일어난다. 여기서는 계정을 만들지도 연결하지도 않는다.
            attributes.put(INTERNAL_USER_ID_ATTRIBUTE, linkUserId);
            attributes.put(PROVIDER_USER_ID_ATTRIBUTE, userInfo.getProviderUserId());
        } else {
            var user = oAuthUserService.findUser(registrationId, userInfo);
            if (user.isPresent()) {
                log.info("[OAuth 사용자 매핑 완료] provider={} userId={}", registrationId, user.get().getId());
                attributes.put(INTERNAL_USER_ID_ATTRIBUTE, user.get().getId());
            } else {
                // 계정이 아직 없다. 인증 주체 이름은 비워 둘 수 없어 가입 대기 토큰을 그대로 쓴다.
                String signupToken = oAuthUserService.startSignup(registrationId, userInfo);
                attributes.put(SIGNUP_TOKEN_ATTRIBUTE, signupToken);
                attributes.put(INTERNAL_USER_ID_ATTRIBUTE, signupToken);
            }
        }

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                INTERNAL_USER_ID_ATTRIBUTE
        );
    }

    /** provider 사용자 정보 조회. 테스트에서 네트워크 호출을 대신하려고 분리했다. */
    OAuth2User loadProviderUser(OAuth2UserRequest userRequest) {
        return super.loadUser(userRequest);
    }
}
