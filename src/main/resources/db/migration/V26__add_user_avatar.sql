-- 사용자 프로필 사진. 워크스페이스 아이콘 이미지(V16)와 같은 방식으로 둔다.
--
-- 바이너리를 users가 아니라 별도 테이블에 두는 이유: 멤버 목록·인가 조회가 사용자마다 최대 1MB를
-- 함께 읽지 않게 한다. users에는 사진 유무 판정과 서빙 ETag·캐시 무효화(avatar_url의 ?v=)에 쓰는
-- 작은 값만 남긴다. 1MB 상한이라 object storage를 쓰지 않는다.
ALTER TABLE users
    ADD COLUMN avatar_content_type character varying(64),
    ADD COLUMN avatar_hash character varying(64);

CREATE TABLE user_avatars (
    user_id character varying(255) NOT NULL,
    image bytea NOT NULL,
    CONSTRAINT user_avatars_pkey PRIMARY KEY (user_id),
    CONSTRAINT user_avatars_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- metadata 두 컬럼은 함께 채워지거나 함께 비어야 한다.
ALTER TABLE users
    ADD CONSTRAINT users_avatar_metadata_paired CHECK (
        (avatar_hash IS NULL) = (avatar_content_type IS NULL)
    );
