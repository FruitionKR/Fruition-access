-- refresh 회전·로그아웃은 token_hash로, 세션 목록·전체 폐기는 user_id + revoked_at IS NULL로 찾는다.
-- 두 경로 모두 인덱스가 없어 user_refresh_tokens 전체를 훑었다.
--
-- token_hash는 SecureRandom 32바이트 토큰의 SHA-256이라 충돌하지 않고, 애플리케이션도 단건(Optional)으로
-- 조회하므로 중복이 있으면 이미 조회가 실패한다. 그래서 UNIQUE로 그 불변식을 DB에서도 강제한다.
--
-- 운영 중 쓰기를 막지 않도록 CONCURRENTLY로 만든다. 트랜잭션 안에서는 실행할 수 없어
-- V31__add_refresh_token_lookup_indexes.sql.conf에서 executeInTransaction=false로 둔다.
-- 실패하면 INVALID 인덱스가 남으므로 DROP INDEX CONCURRENTLY로 지운 뒤 flyway repair 후 다시 실행한다.
-- CONCURRENTLY는 SHARE UPDATE EXCLUSIVE만 잡아 읽기·쓰기를 막지 않지만, 시작 전 열린 트랜잭션이 끝나기를
-- 기다린다(virtualxid lock, lock_timeout 적용). 너무 짧으면 운영 Job이 실패하고 INVALID 인덱스만 남으므로
-- 무한 대기만 막을 만큼 넉넉히 둔다.
SET lock_timeout = '120s';

CREATE UNIQUE INDEX CONCURRENTLY idx_user_refresh_tokens_token_hash
    ON user_refresh_tokens (token_hash);

CREATE INDEX CONCURRENTLY idx_user_refresh_tokens_user_active
    ON user_refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- 트랜잭션 밖에서 바꾼 값은 세션에 남으므로 같은 연결로 이어지는 migration에 새지 않게 되돌린다.
RESET lock_timeout;
