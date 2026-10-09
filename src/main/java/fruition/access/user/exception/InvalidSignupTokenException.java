package fruition.access.user.exception;

/** 소셜 가입 대기 토큰이 없거나 만료됐거나 이미 쓰였다. 소셜 로그인부터 다시 한다. */
public class InvalidSignupTokenException extends RuntimeException {
    public InvalidSignupTokenException() {
        super("가입 시간이 지났습니다. 소셜 로그인부터 다시 진행해 주세요.");
    }
}
