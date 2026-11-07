package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

/** 같은 주소로 초대가 동시에 들어와 대기 중 초대 unique 제약에 걸린 경우. */
public class InvitationInProgressException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public InvitationInProgressException(String email) {
        super("같은 주소로 초대가 이미 진행 중입니다: " + email);
        this.messageArgs = new Object[]{email};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
