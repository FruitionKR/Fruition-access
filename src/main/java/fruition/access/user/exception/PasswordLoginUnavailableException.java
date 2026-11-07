package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

import java.util.List;

public class PasswordLoginUnavailableException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public PasswordLoginUnavailableException(List<String> providers) {
        super("이 이메일은 " + String.join(", ", providers) + " 로그인으로 가입되어 있어 비밀번호를 설정할 수 없습니다.");
        this.messageArgs = new Object[]{String.join(", ", providers)};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
