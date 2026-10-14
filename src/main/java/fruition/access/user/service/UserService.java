package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.dto.EmailAvailabilityRequest;
import fruition.access.user.dto.EmailAvailabilityResponse;
import fruition.access.user.dto.SignupRequest;
import fruition.access.user.dto.SignupResponse;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.exception.EmailRegisteredWithOAuthException;
import fruition.access.user.repository.UserRepository;
import fruition.shared.util.DisplayNames;
import fruition.access.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WorkspaceService workspaceService;
    private final EmailVerificationService emailVerificationService;
    private final UserConsentService userConsentService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       WorkspaceService workspaceService, EmailVerificationService emailVerificationService,
                       UserConsentService userConsentService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.workspaceService = workspaceService;
        this.emailVerificationService = emailVerificationService;
        this.userConsentService = userConsentService;
    }

    @Transactional(readOnly = true)
    public EmailAvailabilityResponse checkEmailAvailability(EmailAvailabilityRequest request) {
        String email = request.email().trim().toLowerCase();
        List<User> accounts = userRepository.findAllByEmail(email);
        // 같은 이메일의 계정이 하나라도 있으면(local이든 소셜이든) 신규 가입할 수 없다.
        // 소셜 계정의 provider는 "그 계정으로 로그인 후 설정에서 연동" 안내용으로 알린다.
        List<String> oauthProviders = accounts.stream()
                .map(User::getProvider)
                .filter(provider -> !User.PROVIDER_LOCAL.equals(provider))
                .distinct()
                .sorted()
                .toList();
        return new EmailAvailabilityResponse(accounts.isEmpty(), oauthProviders);
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase();
        log.info("[회원가입 요청] email={}", email);

        // 유효 토큰 낭비를 막기 위해 중복 검사를 토큰 소비보다 먼저 수행한다.
        if (userRepository.existsByEmailAndProvider(email, User.PROVIDER_LOCAL)) {
            log.warn("[회원가입 실패] reason=duplicate_email email={}", email);
            throw new DuplicateEmailException(email);
        }
        // 같은 이메일의 소셜 계정이 있으면 두 번째 계정을 만들지 않는다. 인증번호 발급에서 걸러지지만 API 직접 호출도 막는다.
        List<String> oauthProviders = userRepository.findProvidersByEmail(email);
        if (!oauthProviders.isEmpty()) {
            log.warn("[회원가입 실패] reason=email_registered_with_oauth email={} providers={}", email, oauthProviders);
            throw new EmailRegisteredWithOAuthException(email, oauthProviders);
        }

        // 동의가 빠진 요청이 인증 토큰을 소비하지 않도록 먼저 확인한다.
        userConsentService.validate(request.ageConfirmed(), request.termsVersion());
        emailVerificationService.consumeForSignup(email, request.verificationToken());

        String displayName = DisplayNames.resolve(request.displayName(), email);
        String displayNameSource = DisplayNames.isPresent(request.displayName()) ? "request" : "email_prefix";

        String userId = "user_" + UUID.randomUUID().toString().replace("-", "");
        User user = new User(userId, email, User.PROVIDER_LOCAL, displayName, passwordEncoder.encode(request.password()));
        // 동의 기록은 JDBC로 users(id)를 참조하므로 users 행을 먼저 flush한다.
        userRepository.saveAndFlush(user);
        userConsentService.record(user.getId(), Boolean.TRUE.equals(request.marketingOptIn()));

        workspaceService.createDefault(user.getId(), user.getDisplayName());
        log.info("[회원가입 성공] userId={} email={} displayNameSource={}", user.getId(), user.getEmail(), displayNameSource);

        return new SignupResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }
}
