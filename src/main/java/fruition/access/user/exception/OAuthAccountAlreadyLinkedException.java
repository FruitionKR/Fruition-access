package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

/** 그 소셜 계정이 이미 다른 사용자에게 연결돼 있거나, 이 사용자에게 같은 provider가 이미 연결돼 있다. */
public class OAuthAccountAlreadyLinkedException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public OAuthAccountAlreadyLinkedException(String provider) {
        super(provider + " 계정이 이미 연결돼 있습니다.");
        this.messageArgs = new Object[]{provider};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
