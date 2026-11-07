package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

public class WorkspaceTrashExpiredException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public WorkspaceTrashExpiredException(String id) {
        super("휴지통 보관 기간이 지나 복구할 수 없는 워크스페이스입니다: id=" + id);
        this.messageArgs = new Object[]{id};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
