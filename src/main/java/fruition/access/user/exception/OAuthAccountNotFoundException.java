package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class OAuthAccountNotFoundException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public OAuthAccountNotFoundException(String provider) {
        super("연결된 " + provider + " 계정이 없습니다.");
        this.messageArgs = new Object[]{provider};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
