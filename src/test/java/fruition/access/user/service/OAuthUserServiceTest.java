package fruition.access.user.service;

import fruition.access.security.oauth.domain.GoogleOAuth2UserInfo;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserOAuthAccount;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.security.oauth.OAuthExchangeCodeStore.PendingLink;
import fruition.access.user.exception.InvalidOAuthLinkCodeException;
import fruition.access.user.exception.OAuthUnlinkNotAllowedException;
import fruition.access.user.exception.OAuthAccountAlreadyLinkedException;
import fruition.access.user.exception.OAuthEmailNotProvidedException;
import fruition.access.user.repository.UserOAuthAccountRepository;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthUserServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserOAuthAccountRepository oauthAccountRepository;
    @Mock WorkspaceService workspaceService;
    @Mock OAuthExchangeCodeStore codeStore;
    @Mock org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clientRegistrationRepository;

    OAuthUserService oAuthUserService;

    @BeforeEach
    void setUp() {
        oAuthUserService = new OAuthUserService(userRepository, oauthAccountRepository, workspaceService,
                codeStore, clientRegistrationRepository);
    }

    private GoogleOAuth2UserInfo googleUserInfo(String sub, String email, String name) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sub", sub);
        attributes.put("email", email);
        attributes.put("name", name);
        return new GoogleOAuth2UserInfo(attributes);
    }

    @Test
    void findOrCreateUser_existingLink_returnsLinkedUser() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.of(new UserOAuthAccount("user_1f9a74af", "google", "google-sub-1")));
        when(userRepository.findById("user_1f9a74af"))
                .thenReturn(Optional.of(new User("user_1f9a74af", "test@example.com", "google", "tes", null)));

        User user = oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", "test@example.com", "Tester"));

        assertThat(user.getId()).isEqualTo("user_1f9a74af");
        verify(userRepository, never()).save(any());
    }

    @Test
    void findOrCreateUser_existingEmailNoLink_createsSeparateAccount() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.empty());

        User user = oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", "test@example.com", "Tester"));

        assertThat(user.getEmail()).isEqualTo("test@example.com");
        assertThat(user.getProvider()).isEqualTo("google");
        verify(userRepository).save(any());
        verify(oauthAccountRepository).save(any());
    }

    @Test
    void findOrCreateUser_newEmail_createsUserAndDefaultWorkspace() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.empty());

        User user = oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", "new@example.com", "New User"));

        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getProvider()).isEqualTo("google");
        assertThat(user.getDisplayName()).isEqualTo("New User");
        assertThat(user.getPasswordHash()).isNull();
        verify(userRepository).save(any());
        verify(workspaceService).createDefault(user.getId(), user.getDisplayName());
        verify(oauthAccountRepository).save(any());
    }

    @Test
    void findOrCreateUser_noEmailProvided_throwsException() {
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> oAuthUserService.findOrCreateUser("google", googleUserInfo("google-sub-1", null, "No Email")))
                .isInstanceOf(OAuthEmailNotProvidedException.class);
    }

    private void localUserWithPassword() {
        when(userRepository.findByIdForUpdate("user_local"))
                .thenReturn(Optional.of(new User("user_local", "a@pusan.ac.kr", User.PROVIDER_LOCAL, "a", "hash")));
    }

    @Test
    void confirmLink_codeStartedByThisUser_addsLinkWithoutCreatingUser() {
        localUserWithPassword();
        when(codeStore.consumeLinkCode("code"))
                .thenReturn(Optional.of(new PendingLink("user_local", "google", "google-sub-1")));
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1")).thenReturn(Optional.empty());

        oAuthUserService.confirmLink("user_local", "code");

        var saved = org.mockito.ArgumentCaptor.forClass(UserOAuthAccount.class);
        verify(oauthAccountRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("user_local");
        assertThat(saved.getValue().getProviderUserId()).isEqualTo("google-sub-1");
        verify(userRepository, never()).save(any());
        verify(workspaceService, never()).createDefault(any(), any());
    }

    @Test
    void confirmLink_codeStartedByAnotherUser_isRejected() {
        // 공격자가 자기 토큰으로 시작한 연동 링크를 피해자가 끝까지 진행해도 피해자 로그인으로는 확정되지 않는다.
        when(codeStore.consumeLinkCode("code"))
                .thenReturn(Optional.of(new PendingLink("user_attacker", "google", "victim-sub")));

        assertThatThrownBy(() -> oAuthUserService.confirmLink("user_victim", "code"))
                .isInstanceOf(InvalidOAuthLinkCodeException.class);
        verify(oauthAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    void startLink_unregisteredProvider_isRejectedBeforeIssuingToken() {
        assertThatThrownBy(() -> oAuthUserService.startLink("user_local", "Google"))
                .isInstanceOf(fruition.access.user.exception.UnsupportedOAuthProviderException.class);
        verify(codeStore, never()).issueLinkToken(any(), any());
    }

    @Test
    void link_googleAccountOfAnotherUser_isRejected() {
        localUserWithPassword();
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1"))
                .thenReturn(Optional.of(new UserOAuthAccount("user_other", "google", "google-sub-1")));

        assertThatThrownBy(() -> oAuthUserService.link("user_local", "google", "google-sub-1"))
                .isInstanceOf(OAuthAccountAlreadyLinkedException.class);
        verify(oauthAccountRepository, never()).saveAndFlush(any());
    }

    @Test
    void link_secondAccountOfSameProvider_isRejected() {
        localUserWithPassword();
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-2")).thenReturn(Optional.empty());
        when(oauthAccountRepository.findAllByUserIdOrderByProvider("user_local"))
                .thenReturn(List.of(new UserOAuthAccount("user_local", "google", "google-sub-1")));

        assertThatThrownBy(() -> oAuthUserService.link("user_local", "google", "google-sub-2"))
                .isInstanceOf(OAuthAccountAlreadyLinkedException.class);
    }

    @Test
    void link_concurrentLinkOfSameAccount_isRejectedAsAlreadyLinked() {
        localUserWithPassword();
        when(oauthAccountRepository.findByProviderAndProviderUserId("google", "google-sub-1")).thenReturn(Optional.empty());
        when(oauthAccountRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> oAuthUserService.link("user_local", "google", "google-sub-1"))
                .isInstanceOf(OAuthAccountAlreadyLinkedException.class);
    }

    @Test
    void unlink_signupProvider_isRejected() {
        // 소셜 전용 계정의 마지막 로그인 수단도 가입 provider이므로 이 규칙으로 지켜진다.
        when(userRepository.findByIdForUpdate("user_g"))
                .thenReturn(Optional.of(new User("user_g", "g@example.com", "google", "g", null)));
        when(oauthAccountRepository.findAllByUserIdOrderByProvider("user_g")).thenReturn(List.of(
                new UserOAuthAccount("user_g", "google", "google-sub-1"),
                new UserOAuthAccount("user_g", "kakao", "kakao-1")));

        assertThatThrownBy(() -> oAuthUserService.unlink("user_g", "google"))
                .isInstanceOf(OAuthUnlinkNotAllowedException.class);
        verify(oauthAccountRepository, never()).delete(any());
    }

    @Test
    void unlink_linkedProvider_removesLink() {
        var link = new UserOAuthAccount("user_local", "google", "google-sub-1");
        localUserWithPassword();
        when(oauthAccountRepository.findAllByUserIdOrderByProvider("user_local")).thenReturn(List.of(link));

        oAuthUserService.unlink("user_local", "google");

        verify(oauthAccountRepository).delete(link);
    }
}
