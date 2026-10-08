package fruition.access.user.service;

import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.domain.OAuth2UserInfo;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserOAuthAccount;
import fruition.access.user.exception.InvalidOAuthLinkCodeException;
import fruition.access.user.exception.OAuthAccountAlreadyLinkedException;
import fruition.access.user.exception.OAuthAccountNotFoundException;
import fruition.access.user.exception.OAuthEmailNotProvidedException;
import fruition.access.user.exception.OAuthUnlinkNotAllowedException;
import fruition.access.user.exception.UnsupportedOAuthProviderException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.repository.UserOAuthAccountRepository;
import fruition.access.user.repository.UserRepository;
import fruition.shared.util.DisplayNames;
import fruition.access.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class OAuthUserService {

    private static final Logger log = LoggerFactory.getLogger(OAuthUserService.class);

    private final UserRepository userRepository;
    private final UserOAuthAccountRepository oauthAccountRepository;
    private final WorkspaceService workspaceService;
    private final OAuthExchangeCodeStore codeStore;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public OAuthUserService(UserRepository userRepository,
                            UserOAuthAccountRepository oauthAccountRepository,
                            WorkspaceService workspaceService,
                            OAuthExchangeCodeStore codeStore,
                            ClientRegistrationRepository clientRegistrationRepository) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.workspaceService = workspaceService;
        this.codeStore = codeStore;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Transactional
    public User findOrCreateUser(String provider, OAuth2UserInfo userInfo) {
        String providerUserId = userInfo.getProviderUserId();
        log.info("[OAuth 로그인 요청] provider={} providerUserId={}", provider, providerUserId);

        var existingLink = oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existingLink.isPresent()) {
            User user = userRepository.findById(existingLink.get().getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "연결된 사용자를 찾을 수 없습니다: userId=" + existingLink.get().getUserId()));
            log.info("[OAuth 로그인 성공] provider={} userId={} link=existing_provider", provider, user.getId());
            return user;
        }

        String email = userInfo.getEmail();
        if (email == null || email.isBlank()) {
            log.warn("[OAuth 로그인 실패] provider={} reason=email_not_provided", provider);
            throw new OAuthEmailNotProvidedException(provider);
        }
        String normalizedEmail = email.trim().toLowerCase();

        // 같은 이메일의 다른 provider 계정과는 합치지 않는다. provider별로 독립 계정을 만든다.
        User user = createUser(provider, normalizedEmail, userInfo.getName());

        oauthAccountRepository.save(new UserOAuthAccount(user.getId(), provider, providerUserId));
        log.info("[OAuth 계정 연결] provider={} userId={}", provider, user.getId());
        return user;
    }

    /** 연동 시작: 등록된 provider면 연동 대상(이 사용자)을 담은 1회용 토큰을 발급한다. */
    public String startLink(String userId, String provider) {
        if (clientRegistrationRepository.findByRegistrationId(provider) == null) {
            throw new UnsupportedOAuthProviderException(provider);
        }
        return codeStore.issueLinkToken(userId, provider);
    }

    /** 연동 확정: 콜백이 넘긴 code를 연동을 시작한 그 사용자가 로그인한 채로 제출해야 연결한다. */
    @Transactional
    public void confirmLink(String userId, String linkCode) {
        var pending = codeStore.consumeLinkCode(linkCode)
                .filter(link -> link.userId().equals(userId))
                .orElseThrow(() -> {
                    log.warn("[OAuth 연동 확정 거부] userId={} reason=invalid_or_foreign_code", userId);
                    return new InvalidOAuthLinkCodeException();
                });
        link(userId, pending.provider(), pending.providerUserId());
    }

    /**
     * 로그인한 사용자에게 소셜 계정을 연결한다. 새 계정을 만들지 않고 계정을 합치지도 않는다.
     * 그 소셜 계정이 다른 사용자에게 이미 연결돼 있거나, 이 사용자에게 같은 provider의 다른 계정이
     * 이미 연결돼 있으면 거절한다. 같은 계정을 다시 연결하면 그대로 성공으로 본다.
     */
    @Transactional
    public void link(String userId, String provider, String providerUserId) {
        userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId));
        var existing = oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existing.isPresent()) {
            if (existing.get().getUserId().equals(userId)) {
                log.info("[OAuth 연동] provider={} userId={} result=already_mine", provider, userId);
                return;
            }
            log.warn("[OAuth 연동 거부] provider={} userId={} reason=linked_to_other_user", provider, userId);
            throw new OAuthAccountAlreadyLinkedException(provider);
        }
        if (findLink(userId, provider).isPresent()) {
            log.warn("[OAuth 연동 거부] provider={} userId={} reason=provider_already_linked", provider, userId);
            throw new OAuthAccountAlreadyLinkedException(provider);
        }
        try {
            oauthAccountRepository.saveAndFlush(new UserOAuthAccount(userId, provider, providerUserId));
        } catch (DataIntegrityViolationException e) {
            // 다른 사용자가 같은 소셜 계정을 동시에 연동했다. 사용자 행 잠금은 서로 달라 여기서 갈린다.
            log.warn("[OAuth 연동 거부] provider={} userId={} reason=concurrent_link", provider, userId);
            throw new OAuthAccountAlreadyLinkedException(provider);
        }
        log.info("[OAuth 연동 성공] provider={} userId={}", provider, userId);
    }

    /** 연결을 해제한다. 가입할 때 쓴 provider는 해제할 수 없다(소셜 전용 계정의 마지막 로그인 수단도 지켜진다). */
    @Transactional
    public void unlink(String userId, String provider) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(() -> new UserNotFoundException(userId));
        UserOAuthAccount target = findLink(userId, provider)
                .orElseThrow(() -> new OAuthAccountNotFoundException(provider));
        if (provider.equals(user.getProvider())) {
            log.warn("[OAuth 연동 해제 거부] provider={} userId={} reason=signup_provider", provider, userId);
            throw new OAuthUnlinkNotAllowedException(provider);
        }
        oauthAccountRepository.delete(target);
        log.info("[OAuth 연동 해제] provider={} userId={}", provider, userId);
    }

    private Optional<UserOAuthAccount> findLink(String userId, String provider) {
        return oauthAccountRepository.findAllByUserIdOrderByProvider(userId).stream()
                .filter(link -> link.getProvider().equals(provider))
                .findFirst();
    }

    private User createUser(String provider, String email, String name) {
        String displayName = DisplayNames.resolve(name, email);
        String displayNameSource = DisplayNames.isPresent(name) ? "provider" : "email_prefix";
        String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
        User user = new User(userId, email, provider, displayName, null);
        userRepository.save(user);
        workspaceService.createDefault(user.getId(), user.getDisplayName());
        log.info("[OAuth 신규 사용자 생성] provider={} userId={} email={} displayNameSource={}",
                provider,
                user.getId(),
                user.getEmail(),
                displayNameSource);
        return user;
    }
}
