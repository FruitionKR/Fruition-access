-- workspace 멤버였던 기간을 남긴다. workspace_members는 제거·탈퇴 시 행을 지워 지난 멤버를 알 수 없다.
-- document의 AI 사용량 정산이 기간 중 멤버였던 사용자(탈퇴·제거 포함)를 찾는 데 쓴다.
-- 초대 수락·생성·제거·탈퇴·사용자/워크스페이스 삭제 cascade가 모두 workspace_members를 거치므로
-- 진입점마다 코드를 두지 않고 trigger 하나로 기록한다. 사용자가 삭제돼도 이력은 남도록 FK를 두지 않는다.
CREATE TABLE workspace_membership_periods (
    workspace_id varchar(255) NOT NULL,
    user_id varchar(255) NOT NULL,
    joined_at timestamptz NOT NULL,
    left_at timestamptz,
    PRIMARY KEY (workspace_id, user_id, joined_at)
);

-- 배포 전에 이미 제거된 멤버는 기록이 없어 복구할 수 없다. 현재 멤버만 채운다.
INSERT INTO workspace_membership_periods (workspace_id, user_id, joined_at)
SELECT workspace_id, user_id, joined_at FROM workspace_members;

CREATE FUNCTION record_workspace_membership_period() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO workspace_membership_periods (workspace_id, user_id, joined_at)
        VALUES (NEW.workspace_id, NEW.user_id, NEW.joined_at)
        ON CONFLICT DO NOTHING;
    ELSE
        UPDATE workspace_membership_periods SET left_at = now()
        WHERE workspace_id = OLD.workspace_id AND user_id = OLD.user_id AND left_at IS NULL;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER record_workspace_membership_period
AFTER INSERT OR DELETE ON workspace_members
FOR EACH ROW EXECUTE FUNCTION record_workspace_membership_period();
