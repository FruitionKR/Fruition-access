package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

public class WorkspaceIconNotFoundException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public WorkspaceIconNotFoundException(String workspaceId) {
        super("워크스페이스에 설정된 아이콘 이미지가 없습니다: workspaceId=" + workspaceId);
        this.messageArgs = new Object[]{workspaceId};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
