package fruition.access.workspace.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** MEMBER에게 개별로 부여하는 권한 항목. OWNER는 항상 전부 가진다. */
public enum WorkspacePermission {
    /** 공유 wiki에 자료를 수집(ingest)한다. LLM 비용이 든다. */
    WIKI_INGEST("wiki.ingest");

    private final String value;

    WorkspacePermission(String value) {
        this.value = value;
    }

    /** API·DB·Redis가 쓰는 표기. */
    @JsonValue
    public String value() {
        return value;
    }

    public static WorkspacePermission fromValue(String value) {
        for (WorkspacePermission permission : values()) {
            if (permission.value.equals(value)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("알 수 없는 권한 항목입니다: " + value);
    }
}
