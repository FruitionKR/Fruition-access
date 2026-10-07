package fruition.access.user.exception;

/** 그 소셜 계정이 이미 다른 사용자에게 연결돼 있거나, 이 사용자에게 같은 provider가 이미 연결돼 있다. */
public class OAuthAccountAlreadyLinkedException extends RuntimeException {
    public OAuthAccountAlreadyLinkedException(String provider) {
        super(provider + " 계정이 이미 연결돼 있습니다.");
    }
}
