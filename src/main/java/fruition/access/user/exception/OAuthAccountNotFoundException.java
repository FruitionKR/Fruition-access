package fruition.access.user.exception;

public class OAuthAccountNotFoundException extends RuntimeException {
    public OAuthAccountNotFoundException(String provider) {
        super("연결된 " + provider + " 계정이 없습니다.");
    }
}
