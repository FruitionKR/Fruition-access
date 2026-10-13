package fruition.access.workspace.exception;

/** OWNER는 항상 모든 권한을 가지므로 권한 항목을 바꿀 수 없다. */
public class OwnerPermissionsFixedException extends RuntimeException {
    public OwnerPermissionsFixedException() {
        super("OWNER는 항상 모든 권한을 가지므로 권한 항목을 바꿀 수 없습니다.");
    }
}
