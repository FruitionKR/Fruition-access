-- 소유자별 워크스페이스 이름 중복 금지를 해제한다.
-- 같은 이름으로 여러 번 생성할 수 있어야 한다는 요구에 따라 V19를 되돌린다.
DROP TRIGGER workspace_owner_names_changed ON workspace_members;
DROP TRIGGER workspace_names_changed ON workspaces;
DROP FUNCTION workspace_owner_names_changed();
DROP FUNCTION workspace_names_changed();
DROP FUNCTION sync_workspace_name_reservations(varchar);
DROP TABLE workspace_name_reservations;
