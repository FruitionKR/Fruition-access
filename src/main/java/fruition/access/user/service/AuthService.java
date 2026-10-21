package fruition.access.user.service;

import fruition.shared.security.JwtTokenProvider;
import fruition.access.security.oauth.OAuthExchangeCodeStore;
import fruition.access.user.domain.User;
import fruition.access.user.domain.UserMfaChallenge;
import fruition.access.user.domain.UserOAuthAccount;
import fruition.access.user.domain.UserRefreshToken;
import fruition.access.user.dto.LoginRequest;
import fruition.access.user.dto.MfaLoginRequest;
import fruition.access.user.dto.LoginResponse;
import fruition.access.user.dto.MeResponse;
import fruition.access.user.dto.OAuthExchangeRequest;
import fruition.access.user.dto.DisplayNameUpdateRequest;
import fruition.access.user.dto.EmailChangeRequest;
import fruition.access.user.dto.EmailVerificationResponse;
import fruition.access.user.dto.PasswordChangeRequest;
import fruition.access.user.dto.PasswordResetRequest;
import fruition.access.user.dto.VerifiedPasswordChangeRequest;
import fruition.access.user.dto.RefreshRequest;
import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.dto.SessionListResponse;
import fruition.access.user.dto.SessionResponse;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidMfaChallengeException;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.PasswordLoginUnavailableException;
import fruition.access.user.exception.SessionNotFoundException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.mfa.MfaService;
import fruition.access.user.repository.UserMfaChallengeRepository;
import fruition.access.user.repository.UserOAuthAccountRepository;
import fruition.access.user.repository.UserRefreshTokenRepository;
import fruition.access.user.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final UserRefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final OAuthExchangeCodeStore oAuthExchangeCodeStore;
    private final EmailVerificationService emailVerificationService;
    private final MfaService mfaService;
    private final UserMfaChallengeRepository mfaChallengeRepository;
    private final UserOAuthAccountRepository oauthAccountRepository;
    private final UserConsentService userConsentService;
    private final Reauthenticator reauthenticator;
    private final ApplicationEventPublisher eventPublisher;
    private final long refreshTokenExpirationSeconds;
    private final long mfaChallengeTtlSeconds;

    public AuthService(UserRepository userRepository,
                       UserRefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       OAuthExchangeCodeStore oAuthExchangeCodeStore,
                       EmailVerificationService emailVerificationService,
                       MfaService mfaService,
                       UserMfaChallengeRepository mfaChallengeRepository,
                       UserOAuthAccountRepository oauthAccountRepository,
                       UserConsentService userConsentService,
                       Reauthenticator reauthenticator,
                       ApplicationEventPublisher eventPublisher,
                       @Value("${app.jwt.refresh-token-expiration-seconds}") long refreshTokenExpirationSeconds,
                       @Value("${app.auth.mfa.challenge-ttl-seconds:300}") long mfaChallengeTtlSeconds) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.oAuthExchangeCodeStore = oAuthExchangeCodeStore;
        this.emailVerificationService = emailVerificationService;
        this.mfaService = mfaService;
        this.mfaChallengeRepository = mfaChallengeRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.userConsentService = userConsentService;
        this.reauthenticator = reauthenticator;
        this.eventPublisher = eventPublisher;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
        this.mfaChallengeTtlSeconds = mfaChallengeTtlSeconds;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        log.info("[로그인 요청] email={}", email);

        User user = userRepository.findByEmailAndProvider(email, User.PROVIDER_LOCAL)
                .orElseThrow(() -> {
                    log.warn("[로그인 실패] reason=unknown_email email={}", email);
                    return new InvalidCredentialsException();
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("[로그인 실패] reason=password_mismatch userId={} email={}", user.getId(), email);
            throw new InvalidCredentialsException();
        }

        if (mfaService.isEnabled(user.getId())) {
            log.info("[로그인 1단계 통과] userId={} mfa=required", user.getId());
            return LoginResponse.mfaRequired(issueMfaChallenge(user));
        }

        LoginResponse response = issueTokenPair(user, Instant.now());
        log.info("[로그인 성공] userId={} email={}", user.getId(), user.getEmail());
        return response;
    }

    @Transactional
    public LoginResponse refresh(RefreshRequest request) {
        UserRefreshToken tokenRow = refreshTokenRepository.findByTokenHash(sha256(request.refreshToken()))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (!tokenRow.isValid()) {
            throw new InvalidRefreshTokenException();
        }
        tokenRow.revoke();

        User user = userRepository.findById(tokenRow.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);

        return issueTokenPair(user, null);
    }

    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByTokenHash(sha256(request.refreshToken()))
                .ifPresent(UserRefreshToken::revoke);
    }

    @Transactional
    public LoginResponse exchangeOAuthCode(OAuthExchangeRequest request) {
        log.info("[OAuth code 교환 요청]");
        String userId = oAuthExchangeCodeStore.consume(request.code(), request.codeVerifier())
                .orElseThrow(() -> {
                    log.warn("[OAuth code 교환 실패] reason=invalid_code");
                    return new InvalidOAuthCodeException();
                });
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("[OAuth code 교환 실패] reason=user_not_found userId={}", userId);
                    return new InvalidOAuthCodeException();
                });
        if (mfaService.isEnabled(user.getId())) {
            return LoginResponse.mfaRequired(issueMfaChallenge(user));
        }

        LoginResponse response = issueTokenPair(user, Instant.now());
        log.info("[OAuth code 교환 성공] userId={} email={}", user.getId(), user.getEmail());
        return response;
    }

    public MeResponse me(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return toMe(user);
    }

    /** {@link MeResponse}를 조립한다. 프로필 사진처럼 다른 서비스가 바꾼 사용자도 같은 응답을 돌려주도록 공개한다. */
    public MeResponse toMe(User user) {
        List<String> providers = oauthAccountRepository.findAllByUserIdOrderByProvider(user.getId()).stream()
                .map(UserOAuthAccount::getProvider)
                .toList();
        return new MeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.avatarUrl(), user.getCreatedAt(),
                providers, userConsentService.consentRequired(user.getId()));
    }

    @Transactional
    public MeResponse updateDisplayName(String userId, DisplayNameUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        user.changeDisplayName(request.displayName().trim());
        log.info("[표시 이름 변경] userId={}", userId);
        return toMe(user);
    }

    /**
     * 로그인 상태에서 비밀번호를 바꾼다.
     *
     * <p>{@code currentRefreshToken}은 요청에 실려 온 refresh 쿠키다. 이 세션만 남기고
     * 나머지를 폐기해, 비밀번호가 샜을 때 다른 기기의 세션을 끊으면서도 방금 인증을 마친
     * 사용자를 로그아웃시키지 않는다. 쿠키가 없으면 전부 폐기한다.
     *
     * <p>MFA를 켠 계정은 토큰과 현재 비밀번호를 함께 가진 사람이 2차 인증 없이 비밀번호를 바꾸지 못하게
     * {@code mfa_code}도 요구한다.
     */
    @Transactional
    public void changePassword(String userId, PasswordChangeRequest request, String currentRefreshToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // OAuth 전용 계정은 password_hash가 없어 "현재 비밀번호"라는 개념이 성립하지 않는다.
        if (user.getPasswordHash() == null) {
            log.warn("[비밀번호 변경 거부] reason=no_password userId={} provider={}", userId, user.getProvider());
            throw new PasswordLoginUnavailableException(List.of(user.getProvider()));
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("[비밀번호 변경 거부] reason=current_password_mismatch userId={}", userId);
            throw new InvalidCredentialsException();
        }
        // 현재 비밀번호를 먼저 확인해, 비밀번호를 모르는 요청이 MFA 시도 횟수를 쓰지 못하게 한다.
        reauthenticator.verifyMfa(user, request.mfaCode());

        user.changePassword(passwordEncoder.encode(request.newPassword()));

        int revoked = revokeOtherSessions(userId, currentRefreshToken);
        log.info("[비밀번호 변경 성공] userId={} revokedSessions={}", userId, revoked);
    }

    /**
     * 로그인 상태의 비밀번호 변경용 인증번호를 세션 사용자의 계정 이메일로 보낸다.
     *
     * <p>받는 주소를 요청 본문에서 받지 않아, 로그인 세션만으로 다른 계정의 메일함에 코드를 보낼 수 없다.
     * 메일 발송이 트랜잭션 밖에서 일어나도록 이 메서드는 트랜잭션을 열지 않는다.
     */
    public EmailVerificationResponse requestPasswordChangeVerification(String userId) {
        User user = passwordUser(userId);
        EmailVerificationResponse response = emailVerificationService.requestForPasswordChange(user.getEmail());
        log.info("[비밀번호 변경 인증 요청] userId={} verificationId={}", userId, response.verificationId());
        return response;
    }

    /**
     * 계정 이메일로 받은 인증번호 토큰으로 본인을 확인하고 비밀번호를 바꾼다.
     *
     * <p>현재 비밀번호를 잊은 로그인 사용자를 위한 경로다. 토큰은 발급 당시의 계정 이메일과
     * {@code password_change} 목적에 묶여 있어, 다른 이메일·다른 목적으로 받은 토큰은 쓸 수 없다.
     * {@link #changePassword}처럼 현재 세션만 남기고 나머지를 폐기한다.
     *
     * <p>MFA를 켠 계정은 세션과 메일함을 함께 가진 사람이 2차 인증 없이 비밀번호를 바꾸지 못하게
     * {@code mfa_code}도 요구한다.
     */
    @Transactional
    public void changePasswordWithVerification(String userId, VerifiedPasswordChangeRequest request,
                                               String currentRefreshToken) {
        User user = passwordUser(userId);
        // 토큰을 먼저 확인해, 유효한 토큰이 없는 요청은 로그인과 공유하는 MFA 시도 예산을 쓰지 못하게 한다.
        // MFA가 거절되면 예외로 이 트랜잭션이 롤백돼 소비도 되돌아가므로, 같은 토큰으로 코드만 고쳐 다시 시도할 수 있다.
        emailVerificationService.consumeForPasswordChange(user.getEmail(), request.verificationToken());
        reauthenticator.verifyMfa(user, request.mfaCode());

        user.changePassword(passwordEncoder.encode(request.newPassword()));

        int revoked = revokeOtherSessions(userId, currentRefreshToken);
        log.info("[비밀번호 변경 성공] method=email_verification userId={} revokedSessions={}", userId, revoked);
    }

    /** 비밀번호 로그인을 쓰는 계정만 돌려준다. OAuth 전용 계정의 비밀번호는 로그인에 쓰이지 않는다. */
    private User passwordUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getPasswordHash() == null) {
            log.warn("[비밀번호 변경 거부] reason=no_password userId={} provider={}", userId, user.getProvider());
            throw new PasswordLoginUnavailableException(List.of(user.getProvider()));
        }
        return user;
    }

    /**
     * 로그인 상태에서 이메일을 바꾼다.
     *
     * <p>새 주소로 받은 인증번호 토큰으로 그 메일함을 통제하는지 확인한다. 계정은
     * 다른 계정(소셜 가입 포함)이 이미 쓰는 주소면 {@code 409 DUPLICATE_EMAIL}로 거절한다.
     * OAuth 로그인은 {@code (provider, provider_user_id)}로 사용자를 찾으므로 이메일이 바뀌어도 끊기지 않는다.
     *
     * <p>먼저 {@link Reauthenticator}로 본인을 다시 확인한다({@code authTime}은 access token의 직접 로그인 시각,
     * refresh로 받은 토큰이면 null). 같은 트랜잭션에서 기존 이메일로 보낼 변경 알림을 outbox에 남기고
     * ({@link EmailChangeNotificationListener}), 발송은 {@link EmailChangeNotificationJob}이 한다.
     */
    @Transactional
    public MeResponse changeEmail(String userId, EmailChangeRequest request, String currentRefreshToken,
                                  Instant authTime) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String newEmail = request.newEmail().trim().toLowerCase(Locale.ROOT);

        // 이메일을 바꾸면 비밀번호 재설정 메일도 새 주소로 가므로 계정을 넘겨줄 수 있다.
        // 세션만 탈취한 사람이 바꾸지 못하게 본인을 다시 확인한다. 거절되면 토큰을 소비하기 전에 끝난다.
        reauthenticator.verify(user, request.currentPassword(), request.mfaCode(), authTime);
        // 다른 계정이 이미 쓰는 주소면 provider와 상관없이 거절한다. 소셜 계정의 주소로 바꾸게 두면
        // 한 이메일에 계정이 둘 생기고, 이후 연동으로 합칠 수 없다(가입 거절과 같은 이유).
        // 본인 확인을 통과한 뒤에만 알려 주고, 토큰을 소비하기 전에 거절해 토큰을 태우지 않는다.
        if (!newEmail.equals(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
            log.warn("[이메일 변경 거부] reason=duplicate_email userId={} provider={}", userId, user.getProvider());
            throw new DuplicateEmailException(newEmail);
        }
        emailVerificationService.consumeForEmailChange(newEmail, request.verificationToken());
        String previousEmail = user.getEmail();

        user.changeEmail(newEmail);
        // 사전 조회 이후 발생한 중복도 커밋 전에 확인해 같은 오류 계약으로 반환한다.
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uq_users_email_provider".equals(violation.getConstraintName())) {
                    throw new DuplicateEmailException(newEmail);
                }
            }
            throw exception;
        }
        int revoked = revokeOtherSessions(userId, currentRefreshToken);
        if (!previousEmail.equals(newEmail)) {
            eventPublisher.publishEvent(new EmailChangedEvent(userId, previousEmail, newEmail, Instant.now()));
        }
        log.info("[이메일 변경 성공] userId={} revokedSessions={}", userId, revoked);
        return toMe(user);
    }

    /**
     * 현재 세션만 남기고 나머지 refresh token을 폐기한다.
     * {@code currentRefreshToken}이 없으면 지킬 세션을 특정할 수 없어 전부 폐기한다.
     */
    private int revokeOtherSessions(String userId, String currentRefreshToken) {
        String keepTokenHash = currentRefreshToken == null ? null : sha256(currentRefreshToken);
        int revoked = 0;
        for (UserRefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)) {
            if (token.getTokenHash().equals(keepTokenHash)) {
                continue;
            }
            token.revoke();
            revoked++;
        }
        return revoked;
    }

    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        emailVerificationService.consumeForPasswordReset(email, request.verificationToken());

        // 인증코드를 통과했으므로 이 시점의 요청자는 해당 메일함을 통제한다. 따라서 OAuth로만
        // 가입된 이메일에는 provider를 알려줘도 열거 위험이 없고, 토큰 오류로 뭉개는 편보다 낫다.
        // 반면 아무 계정도 없는 경우는 계정 존재 여부를 노출하지 않도록 토큰 오류로 유지한다.
        var localAccount = userRepository.findByEmailAndProvider(email, User.PROVIDER_LOCAL);
        if (localAccount.isEmpty()) {
            // 같은 이메일에 여러 provider 계정이 있을 수 있다. 하나만 고르면 사용자가 실제로
            // 쓰는 수단을 못 짚을 수 있으므로 전부 알린다.
            List<String> providers = userRepository.findAllByEmail(email).stream()
                    .map(User::getProvider)
                    .distinct()
                    .sorted()
                    .toList();
            if (providers.isEmpty()) {
                throw new InvalidVerificationTokenException();
            }
            throw new PasswordLoginUnavailableException(providers);
        }
        User user = localAccount.get();

        user.changePassword(passwordEncoder.encode(request.newPassword()));

        // 비밀번호 변경 시 기존 세션(refresh token)을 모두 폐기한다.
        for (UserRefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(user.getId())) {
            token.revoke();
        }
        log.info("[비밀번호 재설정 성공] userId={} email={}", user.getId(), user.getEmail());
    }

    /** 폐기되지 않은 세션 목록. 지금 요청을 보낸 세션에는 {@code current} 표시를 단다. */
    public SessionListResponse sessions(String userId, String currentRefreshToken) {
        String currentHash = currentRefreshToken == null ? null : sha256(currentRefreshToken);
        return new SessionListResponse(
                refreshTokenRepository.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
                        .map(token -> new SessionResponse(
                                token.getId(),
                                token.getUserAgent(),
                                token.getTokenHash().equals(currentHash),
                                token.getCreatedAt(),
                                token.getExpiresAt()))
                        .toList());
    }

    /** 특정 기기 로그아웃. 현재 세션을 지정하면 스스로 로그아웃하는 것이라 허용한다. */
    @Transactional
    public void revokeSession(String userId, Long sessionId) {
        UserRefreshToken token = refreshTokenRepository.findById(sessionId)
                // 남의 세션도 존재를 드러내지 않도록 404로 통일한다.
                .filter(found -> found.getUserId().equals(userId))
                .filter(found -> found.getRevokedAt() == null)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        token.revoke();
        log.info("[세션 폐기] userId={} sessionId={}", userId, sessionId);
    }

    /**
     * 요청의 User-Agent. 발급 경로가 login·refresh·OAuth 교환 셋이라 시그니처를 모두 바꾸는 대신
     * 요청 컨텍스트에서 읽는다({@code BaseExceptionHandler}가 요청 정보를 읽는 방식과 같다).
     * 요청 밖(테스트·비동기)에서는 null이다.
     */
    private String currentUserAgent() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        String userAgent = servletAttributes.getRequest().getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
    }

    /** 로그인 2단계. 코드가 맞아야 토큰을 준다. */
    @Transactional
    public LoginResponse loginMfa(MfaLoginRequest request) {
        UserMfaChallenge challenge = mfaChallengeRepository.findByTokenHash(sha256(request.mfaToken()))
                .filter(UserMfaChallenge::isUsable)
                .orElseThrow(InvalidMfaChallengeException::new);
        User user = userRepository.findById(challenge.getUserId())
                .orElseThrow(() -> new UserNotFoundException(challenge.getUserId()));

        // 코드가 틀리면 challenge를 소비하지 않는다 — 오타 한 번에 로그인을 처음부터 다시 하게 만들지 않는다.
        mfaService.verify(user.getId(), request.code());
        challenge.consume();

        LoginResponse response = issueTokenPair(user, Instant.now());
        log.info("[로그인 성공] userId={} email={} mfa=verified", user.getId(), user.getEmail());
        return response;
    }

    private String issueMfaChallenge(User user) {
        String token = generateOpaqueToken();
        mfaChallengeRepository.save(new UserMfaChallenge(
                "mfc_" + UUID.randomUUID().toString().replace("-", ""),
                user.getId(),
                sha256(token),
                Instant.now().plusSeconds(mfaChallengeTtlSeconds)));
        return token;
    }

    /**
     * 소셜 신규 가입을 마친 사용자에게 로그인 토큰을 준다. 방금 소셜 인증을 거쳤으므로 직접 로그인으로 본다.
     * 다른 탭이 먼저 가입을 끝내 기존 계정이 넘어왔고 그 계정이 MFA를 켰으면 일반 로그인처럼 MFA를 요구한다.
     */
    @Transactional
    public LoginResponse issueForNewOAuthUser(User user) {
        if (mfaService.isEnabled(user.getId())) {
            log.info("[OAuth 가입 후 로그인 1단계 통과] userId={} mfa=required", user.getId());
            return LoginResponse.mfaRequired(issueMfaChallenge(user));
        }
        LoginResponse response = issueTokenPair(user, Instant.now());
        log.info("[OAuth 가입 후 로그인] userId={}", user.getId());
        return response;
    }

    /** {@code authTime}은 직접 로그인했을 때만 넘긴다. refresh로 이어 받은 토큰은 최근 인증으로 보지 않는다. */
    private LoginResponse issueTokenPair(User user, Instant authTime) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), authTime);

        String refreshTokenValue = generateOpaqueToken();
        Instant expiresAt = Instant.now().plusSeconds(refreshTokenExpirationSeconds);
        refreshTokenRepository.save(new UserRefreshToken(
                user.getId(), sha256(refreshTokenValue), expiresAt, currentUserAgent()));

        return LoginResponse.tokens(accessToken, refreshTokenValue, jwtTokenProvider.getAccessTokenExpirationSeconds(),
                userConsentService.consentRequired(user.getId()));
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("해시 계산 실패", e);
        }
    }
}
