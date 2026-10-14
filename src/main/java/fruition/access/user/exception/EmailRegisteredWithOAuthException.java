package fruition.access.user.exception;

import java.util.List;

/** 같은 이메일로 소셜 가입한 계정이 있어 이메일 신규 가입을 거절한다. 기존 계정으로 로그인한 뒤 설정에서 연동해야 한다. */
public class EmailRegisteredWithOAuthException extends RuntimeException {

    private final List<String> oauthProviders;

    public EmailRegisteredWithOAuthException(String email, List<String> oauthProviders) {
        super("이미 소셜 계정(" + String.join(", ", oauthProviders) + ")으로 가입된 이메일입니다. "
                + "그 계정으로 로그인한 뒤 설정에서 연동하세요: email=" + email);
        this.oauthProviders = List.copyOf(oauthProviders);
    }

    public List<String> getOauthProviders() {
        return oauthProviders;
    }
}
