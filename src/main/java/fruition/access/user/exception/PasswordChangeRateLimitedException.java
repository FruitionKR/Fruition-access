package fruition.access.user.exception;

public class PasswordChangeRateLimitedException extends RuntimeException {
    private final long retryAfter;

    public PasswordChangeRateLimitedException(long retryAfter) {
        super("현재 비밀번호 확인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}
