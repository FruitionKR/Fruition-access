package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

/**
 * 로그인한 계정의 이메일이 초대받은 주소와 다를 때. 이 검증이 없으면
 * 링크가 유출되는 순간 아무 계정이나 워크스페이스에 들어올 수 있다.
 */
public class InvitationEmailMismatchException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public InvitationEmailMismatchException(String invitedEmail) {
        super("초대받은 주소(" + invitedEmail + ")의 계정으로 로그인해야 수락할 수 있습니다.");
        this.messageArgs = new Object[]{invitedEmail};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
