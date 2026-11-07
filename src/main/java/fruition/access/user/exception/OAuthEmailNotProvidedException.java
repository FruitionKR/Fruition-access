package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class OAuthEmailNotProvidedException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public OAuthEmailNotProvidedException(String provider) {
        super(provider + " 계정에서 이메일 정보를 가져올 수 없습니다.");
        this.messageArgs = new Object[]{provider};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
