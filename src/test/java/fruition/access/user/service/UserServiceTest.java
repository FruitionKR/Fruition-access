package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.dto.EmailAvailabilityRequest;
import fruition.access.user.dto.SignupRequest;
import fruition.access.user.dto.SignupResponse;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.exception.EmailRegisteredWithOAuthException;
import fruition.access.user.repository.UserRepository;
import fruition.access.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock WorkspaceService workspaceService;
    @Mock EmailVerificationService emailVerificationService;
    @Mock UserConsentService userConsentService;

    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    UserService userService;

    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, workspaceService, emailVerificationService,
                userConsentService, eventPublisher);
    }

    @Test
    void checkEmailAvailability_existingLocalEmail_returnsFalse() {
        when(userRepository.findAllByEmail("test@example.com")).thenReturn(java.util.List.of(
                new User("user_local", "test@example.com", User.PROVIDER_LOCAL, "tes", "hash")));

        var response = userService.checkEmailAvailability(new EmailAvailabilityRequest(" TEST@example.com "));

        assertThat(response.available()).isFalse();
        assertThat(response.oauthProviders()).isEmpty();
    }

    @Test
    void checkEmailAvailability_onlySocialAccounts_isUnavailableAndListsProvidersForGuidance() {
        // 소셜 계정만 있는 이메일도 신규 가입할 수 없다. 로그인 후 연동하도록 안내할 provider를 알린다.
        when(userRepository.findAllByEmail("oauth@example.com")).thenReturn(java.util.List.of(
                new User("user_n", "oauth@example.com", "naver", "n", null),
                new User("user_g", "oauth@example.com", "google", "g", null)));

        var response = userService.checkEmailAvailability(new EmailAvailabilityRequest("oauth@example.com"));

        assertThat(response.available()).isFalse();
        assertThat(response.oauthProviders()).containsExactly("google", "naver");
    }

    @Test
    void checkEmailAvailability_noAccount_returnsTrue() {
        when(userRepository.findAllByEmail("new@example.com")).thenReturn(java.util.List.of());

        var response = userService.checkEmailAvailability(new EmailAvailabilityRequest("new@example.com"));

        assertThat(response.available()).isTrue();
        assertThat(response.oauthProviders()).isEmpty();
    }

    @Test
    void signup_emailRegisteredWithOAuth_rejectsWithoutConsumingTokenOrCreatingUser() {
        when(userRepository.existsByEmailAndProvider("oauth@example.com", User.PROVIDER_LOCAL)).thenReturn(false);
        when(userRepository.findProvidersByEmail("oauth@example.com")).thenReturn(java.util.List.of("google"));

        assertThatThrownBy(() -> userService.signup(new SignupRequest("oauth@example.com", "password123")))
                .isInstanceOfSatisfying(EmailRegisteredWithOAuthException.class, e ->
                        assertThat(e.getOauthProviders()).containsExactly("google"));
        verify(emailVerificationService, org.mockito.Mockito.never()).consumeForSignup(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(userRepository, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(workspaceService, org.mockito.Mockito.never()).createDefault(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void signup_newEmail_createsUserWithHashedPassword() {
        when(userRepository.existsByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("test@example.com", "password123"));

        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.displayName()).isEqualTo("tes");
        assertThat(response.id()).startsWith("user_");
    }

    @Test
    void signup_newEmail_createsDefaultWorkspace() {
        when(userRepository.existsByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("test@example.com", "password123"));

        verify(workspaceService).createDefault(response.id(), "tes");
    }

    @Test
    void signup_duplicateEmail_throwsException() {
        when(userRepository.existsByEmailAndProvider("test@example.com", User.PROVIDER_LOCAL)).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(new SignupRequest("test@example.com", "password123")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void signup_displayName_isFirstThreeCharsOfEmail() {
        when(userRepository.existsByEmailAndProvider("jane.doe@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123"));

        assertThat(response.displayName()).isEqualTo("jan");
    }

    @Test
    void signup_displayNameProvided_usesTrimmedDisplayName() {
        when(userRepository.existsByEmailAndProvider("jane.doe@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123", "  제인  ", "vtoken", true, "2026-10-01", false));

        assertThat(response.displayName()).isEqualTo("제인");
    }

    @Test
    void signup_blankDisplayName_usesFirstThreeCharsOfEmail() {
        when(userRepository.existsByEmailAndProvider("jane.doe@example.com", User.PROVIDER_LOCAL)).thenReturn(false);

        SignupResponse response = userService.signup(new SignupRequest("jane.doe@example.com", "password123", "  ", "vtoken", true, "2026-10-01", false));

        assertThat(response.displayName()).isEqualTo("jan");
    }

    @Test
    void signup_withoutConsent_rejectsBeforeConsumingVerificationToken() {
        org.mockito.Mockito.doThrow(new fruition.access.user.exception.InvalidConsentException())
                .when(userConsentService).validate(null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        userService.signup(new SignupRequest("jane.doe@example.com", "password123")))
                .isInstanceOf(fruition.access.user.exception.InvalidConsentException.class);
        org.mockito.Mockito.verifyNoInteractions(emailVerificationService);
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }
    @Test
    void signup_recordsEmailMethodAndInternalActorOnly() {
        SignupResponse response = userService.signup(new SignupRequest("private@example.test", "password123"));
        var captured = org.mockito.ArgumentCaptor.forClass(fruition.access.analytics.AnalyticsEvent.class);
        verify(eventPublisher).publishEvent(captured.capture());
        var event = captured.getValue();
        assertThat(event.event()).isEqualTo("user_signed_up");
        assertThat(event.actorId()).isEqualTo(response.id());
        assertThat(event.logicalKey()).isEqualTo(response.id());
        assertThat(event.workspaceId()).isNull();
        assertThat(event.properties()).containsExactlyEntriesOf(java.util.Map.of("method", "email"));
        assertThat(event.occurredAt()).isNotNull();
    }
}
