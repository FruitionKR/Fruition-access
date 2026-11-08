package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

public class AlreadyMemberException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public AlreadyMemberException(String email) {
        super("이미 워크스페이스 멤버입니다: " + email);
        this.messageArgs = new Object[]{email};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
