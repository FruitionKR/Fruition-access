package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class UnsupportedUserAvatarException extends RuntimeException implements Localizable {

    private final String messageKey;

    /** {@code messageKey}는 응답 문구의 메시지 번들 키, {@code message}는 로그에 남길 한국어 문구다. */
    public UnsupportedUserAvatarException(String messageKey, String message) {
        super(message);
        this.messageKey = messageKey;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }
}
