package fruition.access.user.exception;

public class UserAvatarTooLargeException extends RuntimeException {
    public UserAvatarTooLargeException(long maxBytes) {
        super("프로필 사진은 " + (maxBytes / 1024) + "KB 이하여야 합니다.");
    }
}
