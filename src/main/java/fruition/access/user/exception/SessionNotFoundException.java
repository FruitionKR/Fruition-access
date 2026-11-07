package fruition.access.user.exception;

import fruition.shared.i18n.Localizable;

/** 남의 세션이거나 이미 폐기된 세션. 존재를 드러내지 않도록 둘 다 404로 답한다. */
public class SessionNotFoundException extends RuntimeException implements Localizable {

    private final Object[] messageArgs;

    public SessionNotFoundException(Long sessionId) {
        super("세션을 찾을 수 없습니다: id=" + sessionId);
        this.messageArgs = new Object[]{String.valueOf(sessionId)};
    }

    @Override
    public Object[] messageArgs() {
        return messageArgs.clone();
    }
}
