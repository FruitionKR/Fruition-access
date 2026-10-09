package fruition.access.user.exception;

import java.util.List;

/** 다른 멤버가 있는 워크스페이스의 유일한 OWNER는 OWNER를 넘기기 전까지 탈퇴할 수 없다. */
public class AccountDeletionBlockedException extends RuntimeException {

    private final List<Workspace> workspaces;

    public AccountDeletionBlockedException(List<Workspace> workspaces) {
        super("다른 멤버가 있는 워크스페이스의 OWNER를 먼저 넘겨 주세요.");
        this.workspaces = workspaces;
    }

    public List<Workspace> getWorkspaces() {
        return workspaces;
    }

    public record Workspace(String id, String name) {}
}
