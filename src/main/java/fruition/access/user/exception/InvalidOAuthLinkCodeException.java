package fruition.access.user.exception;

/** 연동 code가 없거나 만료됐거나, 연동을 시작한 사용자가 아닌 사용자가 확정하려 했다. */
public class InvalidOAuthLinkCodeException extends RuntimeException {
    public InvalidOAuthLinkCodeException() {
        super("유효하지 않거나 만료된 연동 code입니다.");
    }
}
