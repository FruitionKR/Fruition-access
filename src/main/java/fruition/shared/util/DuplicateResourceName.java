package fruition.shared.util;

import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;

/** 이름 고유 제약만 사용자에게 알린다. 그 밖의 무결성 오류는 기존 오류 처리에 맡긴다. */
public final class DuplicateResourceName {
    /** 제약 이름 → 메시지 번들 키. */
    private static final Map<String, String> MESSAGE_KEYS = Map.of(
            "uq_documents_active_name", "error.DUPLICATE_NAME.document",
            "uq_folders_active_name", "error.DUPLICATE_NAME.folder");

    private DuplicateResourceName() {}

    /** 이름 고유 제약 위반이면 문구의 메시지 번들 키, 아니면 null. */
    public static String messageKey(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String key = MESSAGE_KEYS.get(violation.getConstraintName());
                if (key != null) return key;
            }
        }
        return null;
    }
}
