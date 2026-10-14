package fruition.access.user.exception;

public class UserAvatarNotFoundException extends RuntimeException {
    public UserAvatarNotFoundException(String userId) {
        super("사용자에게 설정된 프로필 사진이 없습니다: userId=" + userId);
    }
}
