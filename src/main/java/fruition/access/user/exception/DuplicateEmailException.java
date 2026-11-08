package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

public class DuplicateEmailException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public DuplicateEmailException(String email) {
        super("이미 가입된 이메일입니다: email=" + email);
        this.messageArgs = new Object[]{email};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
