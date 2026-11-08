package fruition.access;

import fruition.access.user.exception.DuplicateEmailException;
import fruition.access.user.dto.EmailRegisteredResponse;
import fruition.access.user.dto.EmailRegisteredWithOAuthResponse;
import fruition.access.user.exception.EmailRegisteredWithOAuthException;
import fruition.access.user.exception.OAuthEmailRegisteredException;
import fruition.access.user.exception.EmailVerificationNotFoundException;
import fruition.access.user.exception.EmailVerificationSendException;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidMfaChallengeException;
import fruition.access.user.exception.InvalidMfaCodeException;
import fruition.access.user.exception.MfaRateLimitedException;
import fruition.access.user.exception.MfaAlreadyEnabledException;
import fruition.access.user.exception.MfaNotEnabledException;
import fruition.access.user.exception.InvalidOAuthCodeException;
import fruition.access.user.exception.InvalidRefreshTokenException;
import fruition.access.user.exception.InvalidVerificationCodeException;
import fruition.access.user.exception.InvalidVerificationTokenException;
import fruition.access.user.exception.LoginRateLimitedException;
import fruition.access.user.exception.PasswordChangeRateLimitedException;
import fruition.access.user.exception.OAuthEmailNotProvidedException;
import fruition.access.user.dto.AccountDeletionBlockedResponse;
import fruition.access.user.exception.AccountDeletionBlockedException;
import fruition.access.user.exception.PasswordLoginUnavailableException;
import fruition.access.user.exception.ReauthenticationRequiredException;
import fruition.access.user.exception.InvalidConsentException;
import fruition.access.user.exception.InvalidSignupTokenException;
import fruition.access.user.exception.SessionNotFoundException;
import fruition.access.user.exception.InvalidOAuthLinkCodeException;
import fruition.access.user.exception.OAuthAccountAlreadyLinkedException;
import fruition.access.user.exception.OAuthUnlinkNotAllowedException;
import fruition.access.user.exception.UnsupportedOAuthProviderException;
import fruition.access.user.exception.OAuthAccountNotFoundException;
import fruition.access.user.exception.UserNotFoundException;
import fruition.access.user.exception.UnsupportedUserAvatarException;
import fruition.access.user.exception.UserAvatarNotFoundException;
import fruition.access.user.exception.UserAvatarTooLargeException;
import fruition.access.user.service.UserAvatarValidator;
import fruition.access.user.exception.VerificationCodeAttemptsExceededException;
import fruition.access.user.exception.VerificationCodeExpiredException;
import fruition.access.user.exception.VerificationRateLimitedException;
import fruition.access.user.exception.EmailAvailabilityRateLimitedException;
import fruition.access.workspace.exception.AlreadyMemberException;
import fruition.access.workspace.exception.InvitationAlreadyAcceptedException;
import fruition.access.workspace.exception.InvitationEmailMismatchException;
import fruition.access.workspace.exception.InvitationExpiredException;
import fruition.access.workspace.exception.InvitationInProgressException;
import fruition.access.workspace.exception.InvitationSendException;
import fruition.access.workspace.exception.InvitationNotFoundException;
import fruition.access.workspace.exception.LastOwnerException;
import fruition.access.workspace.exception.UnsupportedWorkspaceIconException;
import fruition.access.workspace.exception.WorkspaceIconNotFoundException;
import fruition.access.workspace.exception.WorkspaceIconTooLargeException;
import fruition.access.workspace.exception.WorkspaceAccessDeniedException;
import fruition.access.workspace.exception.WorkspaceMemberNotFoundException;
import fruition.access.workspace.exception.OwnerPermissionsFixedException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.exception.WorkspaceTrashExpiredException;
import fruition.access.workspace.service.WorkspaceIconValidator;
import fruition.shared.util.BaseExceptionHandler;
import fruition.shared.util.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** access 앱 전용 예외 매핑. 공통 매핑은 {@link BaseExceptionHandler}에서 상속한다. */
@RestControllerAdvice
public class AccessExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(DuplicateEmailException e) {
        logHandled(e, HttpStatus.CONFLICT, "DUPLICATE_EMAIL");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("DUPLICATE_EMAIL", e));
    }

    @ExceptionHandler(EmailRegisteredWithOAuthException.class)
    public ResponseEntity<EmailRegisteredWithOAuthResponse> handleEmailRegisteredWithOAuth(EmailRegisteredWithOAuthException e) {
        logHandled(e, HttpStatus.CONFLICT, "EMAIL_REGISTERED_WITH_OAUTH");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new EmailRegisteredWithOAuthResponse(
                        error("EMAIL_REGISTERED_WITH_OAUTH", e).error(),
                        e.getOauthProviders()));
    }

    @ExceptionHandler(OAuthEmailRegisteredException.class)
    public ResponseEntity<EmailRegisteredResponse> handleOAuthEmailRegistered(OAuthEmailRegisteredException e) {
        logHandled(e, HttpStatus.CONFLICT, "EMAIL_REGISTERED");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new EmailRegisteredResponse(
                        error("EMAIL_REGISTERED", e).error(),
                        e.getProviders()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("INVALID_CREDENTIALS", e));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("INVALID_REFRESH_TOKEN", e));
    }

    @ExceptionHandler(MfaRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleMfaRateLimited(MfaRateLimitedException e) {
        logHandled(e, HttpStatus.TOO_MANY_REQUESTS, "MFA_RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(error("MFA_RATE_LIMITED", e));
    }

    @ExceptionHandler(InvalidMfaCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMfaCode(InvalidMfaCodeException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("INVALID_MFA_CODE", e));
    }

    @ExceptionHandler(InvalidMfaChallengeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMfaChallenge(InvalidMfaChallengeException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_MFA_CHALLENGE");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("INVALID_MFA_CHALLENGE", e));
    }

    @ExceptionHandler(MfaNotEnabledException.class)
    public ResponseEntity<ErrorResponse> handleMfaNotEnabled(MfaNotEnabledException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "MFA_NOT_ENABLED");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("MFA_NOT_ENABLED", e));
    }

    @ExceptionHandler(MfaAlreadyEnabledException.class)
    public ResponseEntity<ErrorResponse> handleMfaAlreadyEnabled(MfaAlreadyEnabledException e) {
        logHandled(e, HttpStatus.CONFLICT, "MFA_ALREADY_ENABLED");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("MFA_ALREADY_ENABLED", e));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("USER_NOT_FOUND", e));
    }

    @ExceptionHandler(OAuthAccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOAuthAccountNotFound(OAuthAccountNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "OAUTH_ACCOUNT_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("OAUTH_ACCOUNT_NOT_FOUND", e));
    }

    @ExceptionHandler(UnsupportedOAuthProviderException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedOAuthProvider(UnsupportedOAuthProviderException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "UNSUPPORTED_OAUTH_PROVIDER");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("UNSUPPORTED_OAUTH_PROVIDER", e));
    }

    @ExceptionHandler(InvalidOAuthLinkCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOAuthLinkCode(InvalidOAuthLinkCodeException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_OAUTH_LINK_CODE");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("INVALID_OAUTH_LINK_CODE", e));
    }

    @ExceptionHandler(OAuthAccountAlreadyLinkedException.class)
    public ResponseEntity<ErrorResponse> handleOAuthAccountAlreadyLinked(OAuthAccountAlreadyLinkedException e) {
        logHandled(e, HttpStatus.CONFLICT, "OAUTH_ACCOUNT_ALREADY_LINKED");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("OAUTH_ACCOUNT_ALREADY_LINKED", e));
    }

    @ExceptionHandler(OAuthUnlinkNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleOAuthUnlinkNotAllowed(OAuthUnlinkNotAllowedException e) {
        logHandled(e, HttpStatus.CONFLICT, "OAUTH_UNLINK_NOT_ALLOWED");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("OAUTH_UNLINK_NOT_ALLOWED", e));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSessionNotFound(SessionNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("SESSION_NOT_FOUND", e));
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceNotFound(WorkspaceNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("WORKSPACE_NOT_FOUND", e));
    }

    @ExceptionHandler(WorkspaceTrashExpiredException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceTrashExpired(WorkspaceTrashExpiredException e) {
        logHandled(e, HttpStatus.CONFLICT, "WORKSPACE_TRASH_EXPIRED");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("WORKSPACE_TRASH_EXPIRED", e));
    }

    @ExceptionHandler(WorkspaceMemberNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceMemberNotFound(WorkspaceMemberNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "WORKSPACE_MEMBER_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("WORKSPACE_MEMBER_NOT_FOUND", e));
    }

    @ExceptionHandler(WorkspaceAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceAccessDenied(WorkspaceAccessDeniedException e) {
        logHandled(e, HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED");
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(error("WORKSPACE_ACCESS_DENIED", e));
    }

    @ExceptionHandler(LastOwnerException.class)
    public ResponseEntity<ErrorResponse> handleLastOwner(LastOwnerException e) {
        logHandled(e, HttpStatus.CONFLICT, "LAST_OWNER");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("LAST_OWNER", e));
    }

    @ExceptionHandler(OwnerPermissionsFixedException.class)
    public ResponseEntity<ErrorResponse> handleOwnerPermissionsFixed(OwnerPermissionsFixedException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "OWNER_PERMISSIONS_FIXED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("OWNER_PERMISSIONS_FIXED", e));
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleInvitationNotFound(InvitationNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("INVITATION_NOT_FOUND", e));
    }

    @ExceptionHandler(InvitationExpiredException.class)
    public ResponseEntity<ErrorResponse> handleInvitationExpired(InvitationExpiredException e) {
        logHandled(e, HttpStatus.GONE, "INVITATION_EXPIRED");
        return ResponseEntity
                .status(HttpStatus.GONE)
                .body(error("INVITATION_EXPIRED", e));
    }

    @ExceptionHandler(InvitationAlreadyAcceptedException.class)
    public ResponseEntity<ErrorResponse> handleInvitationAlreadyAccepted(InvitationAlreadyAcceptedException e) {
        logHandled(e, HttpStatus.CONFLICT, "INVITATION_ALREADY_ACCEPTED");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("INVITATION_ALREADY_ACCEPTED", e));
    }

    @ExceptionHandler(InvitationEmailMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvitationEmailMismatch(InvitationEmailMismatchException e) {
        logHandled(e, HttpStatus.FORBIDDEN, "INVITATION_EMAIL_MISMATCH");
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(error("INVITATION_EMAIL_MISMATCH", e));
    }

    @ExceptionHandler(InvitationInProgressException.class)
    public ResponseEntity<ErrorResponse> handleInvitationInProgress(InvitationInProgressException e) {
        logHandled(e, HttpStatus.CONFLICT, "INVITATION_IN_PROGRESS");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("INVITATION_IN_PROGRESS", e));
    }

    @ExceptionHandler(InvitationSendException.class)
    public ResponseEntity<ErrorResponse> handleInvitationSend(InvitationSendException e) {
        logHandled(e, HttpStatus.BAD_GATEWAY, "INVITATION_SEND_FAILED");
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(error("INVITATION_SEND_FAILED", e));
    }

    @ExceptionHandler(AlreadyMemberException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyMember(AlreadyMemberException e) {
        logHandled(e, HttpStatus.CONFLICT, "ALREADY_MEMBER");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(error("ALREADY_MEMBER", e));
    }

    @ExceptionHandler(UnsupportedWorkspaceIconException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedWorkspaceIcon(UnsupportedWorkspaceIconException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "UNSUPPORTED_WORKSPACE_ICON");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("UNSUPPORTED_WORKSPACE_ICON", e));
    }

    @ExceptionHandler(WorkspaceIconTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceIconTooLarge(WorkspaceIconTooLargeException e) {
        logHandled(e, HttpStatus.PAYLOAD_TOO_LARGE, "WORKSPACE_ICON_TOO_LARGE");
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("WORKSPACE_ICON_TOO_LARGE", e));
    }

    @ExceptionHandler(WorkspaceIconNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkspaceIconNotFound(WorkspaceIconNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "WORKSPACE_ICON_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("WORKSPACE_ICON_NOT_FOUND", e));
    }

    @ExceptionHandler(UnsupportedUserAvatarException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedUserAvatar(UnsupportedUserAvatarException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "UNSUPPORTED_USER_AVATAR");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("UNSUPPORTED_USER_AVATAR", e));
    }

    @ExceptionHandler(UserAvatarTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleUserAvatarTooLarge(UserAvatarTooLargeException e) {
        logHandled(e, HttpStatus.PAYLOAD_TOO_LARGE, "USER_AVATAR_TOO_LARGE");
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("USER_AVATAR_TOO_LARGE", e));
    }

    @ExceptionHandler(UserAvatarNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserAvatarNotFound(UserAvatarNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "USER_AVATAR_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("USER_AVATAR_NOT_FOUND", e));
    }

    /**
     * Spring multipart 한도(max-file-size 2MB)에 먼저 걸린 업로드. 검증기의 1MB 한도와 같은 413이어야
     * 클라이언트가 "파일이 없다"는 400 INVALID_REQUEST와 구분한다. 어느 업로드인지는 경로로 가른다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e, HttpServletRequest request) {
        boolean avatar = request.getRequestURI().endsWith("/avatar");
        String code = avatar ? "USER_AVATAR_TOO_LARGE" : "WORKSPACE_ICON_TOO_LARGE";
        Exception tooLarge = avatar
                ? new UserAvatarTooLargeException(UserAvatarValidator.MAX_BYTES)
                : new WorkspaceIconTooLargeException(WorkspaceIconValidator.MAX_BYTES);
        logHandled(e, HttpStatus.PAYLOAD_TOO_LARGE, code);
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error(code, tooLarge));
    }

    @ExceptionHandler(InvalidOAuthCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOAuthCode(InvalidOAuthCodeException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_OAUTH_CODE");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("INVALID_OAUTH_CODE", e));
    }

    @ExceptionHandler(OAuthEmailNotProvidedException.class)
    public ResponseEntity<ErrorResponse> handleOAuthEmailNotProvided(OAuthEmailNotProvidedException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "OAUTH_EMAIL_NOT_PROVIDED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("OAUTH_EMAIL_NOT_PROVIDED", e));
    }

    @ExceptionHandler(InvalidConsentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidConsent(InvalidConsentException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("CONSENT_REQUIRED", e));
    }

    @ExceptionHandler(InvalidSignupTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSignupToken(InvalidSignupTokenException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "INVALID_SIGNUP_TOKEN");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("INVALID_SIGNUP_TOKEN", e));
    }

    @ExceptionHandler(ReauthenticationRequiredException.class)
    public ResponseEntity<ErrorResponse> handleReauthenticationRequired(ReauthenticationRequiredException e) {
        logHandled(e, HttpStatus.UNAUTHORIZED, "REAUTHENTICATION_REQUIRED");
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(error("REAUTHENTICATION_REQUIRED", e));
    }

    @ExceptionHandler(AccountDeletionBlockedException.class)
    public ResponseEntity<AccountDeletionBlockedResponse> handleAccountDeletionBlocked(AccountDeletionBlockedException e) {
        logHandled(e, HttpStatus.CONFLICT, "SOLE_OWNER_OF_SHARED_WORKSPACE");
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new AccountDeletionBlockedResponse(
                        error("SOLE_OWNER_OF_SHARED_WORKSPACE", e).error(),
                        e.getWorkspaces()));
    }

    @ExceptionHandler(PasswordLoginUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePasswordLoginUnavailable(PasswordLoginUnavailableException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "PASSWORD_LOGIN_UNAVAILABLE");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("PASSWORD_LOGIN_UNAVAILABLE", e));
    }

    @ExceptionHandler(EmailVerificationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerificationNotFound(EmailVerificationNotFoundException e) {
        logHandled(e, HttpStatus.NOT_FOUND, "VERIFICATION_NOT_FOUND");
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(error("VERIFICATION_NOT_FOUND", e));
    }

    @ExceptionHandler(InvalidVerificationCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationCode(InvalidVerificationCodeException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_CODE");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("INVALID_VERIFICATION_CODE", e));
    }

    @ExceptionHandler(VerificationCodeExpiredException.class)
    public ResponseEntity<ErrorResponse> handleVerificationCodeExpired(VerificationCodeExpiredException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_EXPIRED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("VERIFICATION_CODE_EXPIRED", e));
    }

    @ExceptionHandler(VerificationCodeAttemptsExceededException.class)
    public ResponseEntity<ErrorResponse> handleVerificationCodeAttemptsExceeded(VerificationCodeAttemptsExceededException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_ATTEMPTS_EXCEEDED");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("VERIFICATION_CODE_ATTEMPTS_EXCEEDED", e));
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationToken(InvalidVerificationTokenException e) {
        logHandled(e, HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION_TOKEN");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(error("INVALID_VERIFICATION_TOKEN", e));
    }

    @ExceptionHandler(VerificationRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleVerificationRateLimited(VerificationRateLimitedException e) {
        logHandled(e, HttpStatus.TOO_MANY_REQUESTS, "VERIFICATION_RATE_LIMITED");
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(error("VERIFICATION_RATE_LIMITED", e));
    }

    @ExceptionHandler(EmailAvailabilityRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleEmailAvailabilityRateLimited(EmailAvailabilityRateLimitedException e) {
        logHandled(e, HttpStatus.TOO_MANY_REQUESTS, "EMAIL_AVAILABILITY_RATE_LIMITED");
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(error("EMAIL_AVAILABILITY_RATE_LIMITED", e));
    }

    @ExceptionHandler(LoginRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleLoginRateLimited(LoginRateLimitedException e) {
        logHandled(e, HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(error("LOGIN_RATE_LIMITED", e));
    }

    @ExceptionHandler(PasswordChangeRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handlePasswordChangeRateLimited(PasswordChangeRateLimitedException e) {
        logHandled(e, HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_CHANGE_RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfter()))
                .body(error("PASSWORD_CHANGE_RATE_LIMITED", e));
    }

    @ExceptionHandler(EmailVerificationSendException.class)
    public ResponseEntity<ErrorResponse> handleEmailVerificationSend(EmailVerificationSendException e) {
        logHandled(e, HttpStatus.BAD_GATEWAY, "EMAIL_SEND_FAILED");
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(error("EMAIL_SEND_FAILED", e));
    }
}
