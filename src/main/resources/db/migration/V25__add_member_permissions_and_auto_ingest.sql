-- 공유 wiki(Fruition-ai ADR 0033)에서 ingest는 워크스페이스 전원이 보는 wiki를 바꾸고 LLM 비용을 쓴다.
-- 역할(OWNER/MEMBER)을 늘리지 않고 멤버별 권한 항목으로 나눈다. OWNER는 코드에서 항상 전부로 계산하므로 저장하지 않는다.
-- 멤버가 제거되면 권한도 함께 사라지고, 재초대하면 권한 없이 시작한다.
-- 서비스 출시 전이라 기존 멤버에게 권한을 이관하지 않는다. 기존 멤버도 권한 없이 시작하고 OWNER가 부여한다.
CREATE TABLE workspace_member_permissions (
    workspace_id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    permission varchar(64) NOT NULL,
    PRIMARY KEY (workspace_id, user_id, permission),
    FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_members (workspace_id, user_id) ON DELETE CASCADE,
    CONSTRAINT workspace_member_permissions_permission_check CHECK (permission IN ('wiki.ingest', 'wiki.lint', 'skill.author', 'wiki.restore', 'document.convert'))
);

-- 업로드 시 자동 ingest 여부. 비용이 드는 동작이라 기본은 끈다.
ALTER TABLE workspaces ADD COLUMN auto_ingest_on_upload boolean NOT NULL DEFAULT false;
