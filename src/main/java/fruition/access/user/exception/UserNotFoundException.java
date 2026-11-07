package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class UserNotFoundException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public UserNotFoundException(String userId) {
        super("사용자를 찾을 수 없습니다: id=" + userId);
        this.messageArgs = new Object[]{userId};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
