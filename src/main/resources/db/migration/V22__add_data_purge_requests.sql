-- 회원 탈퇴 뒤 document에 요청할 데이터 파기 목록.
-- 탈퇴 트랜잭션에서 넣고, 정리 작업이 document 파기 API를 호출해 성공하면 지운다. 실패하면 backoff로 다시 시도한다.
-- kind = 'user': 공유 워크스페이스에 남은 그 사용자의 개인 데이터
-- kind = 'workspace': 혼자 쓰던 워크스페이스 전체. document 파기가 끝나면 workspaces 행도 지운다.
-- users가 이미 지워진 뒤에도 남아야 하므로 FK를 두지 않는다.
CREATE TABLE data_purge_requests (
    kind            varchar(16)              NOT NULL,
    target_id       varchar(255)             NOT NULL,
    attempts        integer                  NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL DEFAULT now(),
    last_error      varchar(1000),
    created_at      timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT data_purge_requests_pkey PRIMARY KEY (kind, target_id),
    CONSTRAINT data_purge_requests_kind_check CHECK (kind IN ('user', 'workspace'))
);

CREATE INDEX idx_data_purge_requests_next_attempt ON data_purge_requests (next_attempt_at);
