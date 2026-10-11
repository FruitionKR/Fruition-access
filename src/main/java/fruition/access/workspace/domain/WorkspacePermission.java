package fruition.access.workspace.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** MEMBER에게 개별로 부여하는 권한 항목. OWNER는 항상 전부 가진다. */
public enum WorkspacePermission {
    /** 편입(ingest)과 채팅 내용의 위키 내보내기. 공유 wiki를 바꾸고 LLM 비용이 든다. */
    WIKI_INGEST("wiki.ingest"),
    /** 위키 정리(lint). 공유 wiki를 바꾸고 LLM 비용이 든다. */
    WIKI_LINT("wiki.lint"),
    /** 스킬 작성·게시. */
    SKILL_AUTHOR("skill.author"),
    /** 남이 실행한 AI 작업 되돌리기. 실행자 본인과 OWNER는 항상 되돌릴 수 있고, 이 항목은 타인의 작업에만 필요하다. */
    WIKI_RESTORE("wiki.restore"),
    /** 남이 올린 PDF 변환. 업로더 본인과 OWNER는 항상 변환할 수 있고, 이 항목은 타인의 PDF에만 필요하다. */
    DOCUMENT_CONVERT("document.convert");

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
