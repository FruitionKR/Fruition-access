package fruition.access.user.exception;

/**
 * 가입할 때 쓴 provider는 해제할 수 없다. 해제하면 그 provider로 다시 로그인할 때 같은 (email, provider)
 * 계정을 새로 만들려다 막히고, 소셜 전용 계정은 마지막 로그인 수단을 잃는다.
 */
public class OAuthUnlinkNotAllowedException extends RuntimeException {
    public OAuthUnlinkNotAllowedException(String provider) {
        super(provider + "는 가입할 때 쓴 로그인 수단이라 연결을 해제할 수 없습니다.");
    }
}
