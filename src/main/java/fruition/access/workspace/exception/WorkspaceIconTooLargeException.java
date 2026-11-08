package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

public class WorkspaceIconTooLargeException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public WorkspaceIconTooLargeException(long maxBytes) {
        super("아이콘 이미지는 " + (maxBytes / 1024) + "KB 이하여야 합니다.");
        this.messageArgs = new Object[]{String.valueOf(maxBytes / 1024)};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
