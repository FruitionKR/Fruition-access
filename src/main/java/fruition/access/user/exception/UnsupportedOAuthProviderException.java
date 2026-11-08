package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class UnsupportedOAuthProviderException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public UnsupportedOAuthProviderException(String provider) {
        super("지원하지 않는 소셜 로그인입니다: " + provider);
        this.messageArgs = new Object[]{provider};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
