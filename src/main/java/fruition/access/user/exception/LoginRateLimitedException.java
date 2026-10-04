package fruition.access.user.exception;

public class LoginRateLimitedException extends RuntimeException {
    private final long retryAfter;

    public LoginRateLimitedException(long retryAfter) {
        super("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}
