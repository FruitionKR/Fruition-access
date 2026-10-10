package fruition.access.user.exception;

/** 최근 직접 로그인한 기록이 없어 다시 로그인해야 한다. 비밀번호가 없는 소셜 계정의 탈퇴에서 쓴다. */
public class ReauthenticationRequiredException extends RuntimeException {
    public ReauthenticationRequiredException() {
        super("본인 확인을 위해 다시 로그인한 뒤 시도해 주세요.");
    }
}
