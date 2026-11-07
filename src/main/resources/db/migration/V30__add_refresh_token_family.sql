-- refresh token 회전 계보. 한 번의 로그인에서 회전으로 이어진 토큰은 같은 family_id를 가진다.
-- 세션을 폐기할 때 동시에 진행된 회전이 막 만든 후속 토큰까지 함께 끊으려면 계보가 필요하다.
--
-- 기존 행은 각자 하나의 계보로 본다(행마다 다른 값). DEFAULT는 배포 중 이 컬럼을 모르는 이전 버전
-- 인스턴스가 넣는 행을 위한 것이고, 애플리케이션은 항상 값을 직접 넣는다.
ALTER TABLE user_refresh_tokens
    ADD COLUMN family_id character varying(64) NOT NULL DEFAULT md5(random()::text || clock_timestamp()::text);

CREATE INDEX idx_user_refresh_tokens_family ON user_refresh_tokens (family_id) WHERE revoked_at IS NULL;
