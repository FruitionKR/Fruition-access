package fruition.access.user.service;

import fruition.access.user.domain.User;
import fruition.access.user.exception.InvalidCredentialsException;
import fruition.access.user.exception.InvalidMfaCodeException;
import fruition.access.user.exception.ReauthenticationRequiredException;
import fruition.access.user.mfa.MfaService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 계정을 넘겨줄 수 있는 민감한 변경(회원 탈퇴, 이메일 변경) 전에 본인을 다시 확인한다.
 *
 * <p>비밀번호 계정은 현재 비밀번호, 비밀번호가 없는 소셜 계정은 {@link #RECENT_LOGIN} 안의 직접 로그인
 * (refresh로 받은 access token이면 {@code authTime}이 없다)을 요구한다. MFA를 켰으면 코드도 확인한다.
 * 비밀번호 변경 경로는 현재 비밀번호나 이메일 인증번호를 따로 확인하므로 {@link #verifyMfa}만 쓴다.
 */
@Component
public class Reauthenticator {

    static final Duration RECENT_LOGIN = Duration.ofMinutes(10);

    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;

    public Reauthenticator(PasswordEncoder passwordEncoder, MfaService mfaService) {
        this.passwordEncoder = passwordEncoder;
        this.mfaService = mfaService;
    }

    public void verify(User user, String password, String mfaCode, Instant authTime) {
        if (user.getPasswordHash() != null) {
            if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
                throw new InvalidCredentialsException();
            }
        } else if (authTime == null || authTime.isBefore(Instant.now().minus(RECENT_LOGIN))) {
            throw new ReauthenticationRequiredException();
        }
        verifyMfa(user, mfaCode);
    }

    /** MFA를 켠 계정이면 코드를 확인한다. 코드 검증은 {@link MfaService}의 사용자별 시도 제한을 받는다. */
    public void verifyMfa(User user, String mfaCode) {
        if (mfaService.isEnabled(user.getId())) {
            if (mfaCode == null || mfaCode.isBlank()) {
                throw new InvalidMfaCodeException();
            }
            mfaService.verify(user.getId(), mfaCode);
        }
    }
}
