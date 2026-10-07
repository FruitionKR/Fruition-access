package fruition.access.user.exception;

public class UnsupportedOAuthProviderException extends RuntimeException {
    public UnsupportedOAuthProviderException(String provider) {
        super("지원하지 않는 소셜 로그인입니다: " + provider);
    }
}
