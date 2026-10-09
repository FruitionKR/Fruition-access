package fruition.access.user.exception;

/** 만 18세 이상 확인이나 현재 버전 이용약관 동의가 빠졌다. */
public class InvalidConsentException extends RuntimeException {
    public InvalidConsentException() {
        super("만 18세 이상 확인과 현재 이용약관 동의가 필요합니다.");
    }
}
