package fruition.access.user.exception;

import java.util.List;

/** 같은 이메일의 계정(local 또는 다른 provider)이 이미 있어 소셜 신규 가입을 거절한다. */
public class OAuthEmailRegisteredException extends RuntimeException {

    private final List<String> providers;

    public OAuthEmailRegisteredException(String provider, List<String> providers) {
        super("이미 다른 방법(" + String.join(", ", providers) + ")으로 가입된 이메일입니다. "
                + "기존 계정으로 로그인한 뒤 설정에서 " + provider + " 계정을 연동하세요.");
        this.providers = List.copyOf(providers);
    }

    /** 같은 이메일로 가입한 계정의 provider(이름순). 이메일 가입 계정은 {@code local}. */
    public List<String> getProviders() {
        return providers;
    }
}
