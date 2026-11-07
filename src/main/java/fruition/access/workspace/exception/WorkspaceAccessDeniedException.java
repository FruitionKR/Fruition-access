package fruition.access.workspace.exception;

import fruition.shared.i18n.Localizable;

/** 멤버지만 해당 작업에 필요한 역할(OWNER)이 아닐 때. 비멤버는 존재를 숨기려 404로 처리한다. */
public class WorkspaceAccessDeniedException extends RuntimeException implements Localizable {

    private final String messageKey;

    /** {@code messageKey}는 응답 문구의 메시지 번들 키, {@code message}는 로그에 남길 한국어 문구다. */
    public WorkspaceAccessDeniedException(String messageKey, String message) {
        super(message);
        this.messageKey = messageKey;
    }

    @Override
    public String messageKey() {
        return messageKey;
    }
}
