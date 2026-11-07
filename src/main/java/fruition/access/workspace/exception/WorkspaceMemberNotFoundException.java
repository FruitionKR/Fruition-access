package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

public class WorkspaceMemberNotFoundException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public WorkspaceMemberNotFoundException(String workspaceId, String userId) {
        super("워크스페이스 멤버를 찾을 수 없습니다: workspaceId=" + workspaceId + " userId=" + userId);
        this.messageArgs = new Object[]{workspaceId, userId};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
