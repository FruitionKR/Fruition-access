-- 공유 wiki(Fruition-ai ADR 0033)에서 ingest는 워크스페이스 전원이 보는 wiki를 바꾸고 LLM 비용을 쓴다.
-- 역할(OWNER/MEMBER)을 늘리지 않고 멤버별 권한 항목으로 나눈다. OWNER는 코드에서 항상 전부로 계산하므로 저장하지 않는다.
-- 멤버가 제거되면 권한도 함께 사라지고, 재초대하면 권한 없이 시작한다.
CREATE TABLE workspace_member_permissions (
    workspace_id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    permission varchar(64) NOT NULL,
    PRIMARY KEY (workspace_id, user_id, permission),
    FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_members (workspace_id, user_id) ON DELETE CASCADE,
    CONSTRAINT workspace_member_permissions_permission_check CHECK (permission IN ('wiki.ingest'))
);

-- 배포 전에는 모든 멤버가 ingest를 할 수 있었다. 배포 당일 기존 MEMBER가 갑자기 막히지 않도록 부여해 둔다.
INSERT INTO workspace_member_permissions (workspace_id, user_id, permission)
SELECT workspace_id, user_id, 'wiki.ingest' FROM workspace_members WHERE role = 'MEMBER';

-- 업로드 시 자동 ingest 여부. 비용이 드는 동작이라 기본은 끈다.
ALTER TABLE workspaces ADD COLUMN auto_ingest_on_upload boolean NOT NULL DEFAULT false;
