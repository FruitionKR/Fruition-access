package fruition.access.workspace.exception;

public class WorkspaceTrashExpiredException extends RuntimeException {
    public WorkspaceTrashExpiredException(String id) {
        super("휴지통 보관 기간이 지나 복구할 수 없는 워크스페이스입니다: id=" + id);
    }
}
