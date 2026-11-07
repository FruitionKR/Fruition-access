package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

public class WorkspaceNotFoundException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public WorkspaceNotFoundException(String id) {
        super("워크스페이스를 찾을 수 없습니다: id=" + id);
        this.messageArgs = new Object[]{id};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
