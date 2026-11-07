package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class UserAvatarNotFoundException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public UserAvatarNotFoundException(String userId) {
        super("사용자에게 설정된 프로필 사진이 없습니다: userId=" + userId);
        this.messageArgs = new Object[]{userId};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
