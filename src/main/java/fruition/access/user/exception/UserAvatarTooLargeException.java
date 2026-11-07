package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class UserAvatarTooLargeException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public UserAvatarTooLargeException(long maxBytes) {
        super("프로필 사진은 " + (maxBytes / 1024) + "KB 이하여야 합니다.");
        this.messageArgs = new Object[]{String.valueOf(maxBytes / 1024)};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
