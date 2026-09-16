# Access 데이터 모델

DB migration 원본은 `src/main/resources/db/migration/`입니다. 다른 서비스의 DB를 직접 수정하지 않습니다.

### access_db (access-svc)

| 테이블 | 소유 | 용도 | 핵심 컬럼/관계 |
|---|---|---|---|
| users | access-svc | 사용자 계정 | `(email, provider)` UK, `provider`는 계정을 만든 수단(`local`/OAuth 등록 ID), `password_hash`(OAuth 전용은 NULL) |
| user_oauth_accounts | access-svc | OAuth provider 연결 | users 1:N(FK `ON DELETE CASCADE`), `(provider, provider_user_id)` |
| user_refresh_tokens | access-svc | JWT refresh token | `token_hash`(SHA-256), `revoked_at`으로 탈취 감지, `user_agent`(세션 목록의 기기 구분. 컬럼 신설 이전 발급분은 NULL) |
| user_mfa | access-svc | TOTP 설정 | PK/FK `user_id`(삭제 cascade), `secret_cipher`·`secret_nonce`(AES-GCM. 검증에 원문이 필요해 해시로 둘 수 없다), `activated_at`(NULL이면 등록만 하고 미활성이라 로그인을 막지 않음), `last_used_counter`(같은 시간 창 재사용 차단) |
| user_mfa_recovery_codes | access-svc | 1회용 복구 코드 | `code_hash`(SHA-256, 원문 미저장), `consumed_at`. 미소비분에 partial index |
| user_mfa_challenges | access-svc | 로그인 2단계 중간 상태 | `token_hash` UK, `expires_at`(기본 300초), `consumed_at`. 비밀번호는 통과했지만 코드를 아직 못 받은 상태다 |
| workspaces | access-svc | 격리 단위 | 문서·Wiki·채팅의 소속 기준, 아이콘 `icon_emoji`·`icon_image_hash`·`icon_image_content_type`(이모지와 이미지는 CHECK 제약으로 배타), workspace 설정 snapshot인 `ingest_lint_provider`·`ingest_lint_model`(새 workspace 기본값 `gemini/gemini-3.1-flash-lite`) |
| workspace_icons | access-svc | 아이콘 이미지 바이너리 | PK/FK `workspace_id` → `workspaces(id)`(삭제 cascade), `image bytea`. 목록 조회가 바이너리를 함께 읽지 않도록 workspaces에서 분리했다. 1MB 상한이라 object storage를 쓰지 않는다 |
| workspace_members | access-svc | 멤버십(N:M 대비) | 복합 PK `(workspace_id, user_id)`, `role`(owner/member) |
| workspace_name_reservations | access-svc | 소유자별 활성 워크스페이스 이름 점유 | PK `(workspace_id, user_id)`, unique `(user_id, normalized_name)`. V19 트리거가 이름·삭제 상태·멤버십 변경과 같은 트랜잭션에서 모든 OWNER의 점유를 갱신한다 |
| workspace_invitations | access-svc | 이메일 초대(수락 전 상태) | `token_hash`(SHA-256, 원문 미저장), `expires_at`, `accepted_at`/`accepted_by`/`revoked_at`. 대기 중 초대는 `(workspace_id, email)` partial unique라 재초대는 새 행이 아니라 재발송이다. 계정이 `(email, provider)`로 분리돼 있어 어느 계정이 멤버가 될지는 수락 시점에 정해진다 |
