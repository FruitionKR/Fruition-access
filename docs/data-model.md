# Access 데이터 모델

DB migration 원본은 `src/main/resources/db/migration/`입니다. 다른 서비스의 DB를 직접 수정하지 않습니다.

### access_db (access-svc)

| 테이블 | 소유 | 용도 | 핵심 컬럼/관계 |
|---|---|---|---|
| users | access-svc | 사용자 계정 | `(email, provider)` UK, `provider`는 계정을 만든 수단(`local`/OAuth 등록 ID), `password_hash`(OAuth 전용은 NULL) |
| user_oauth_accounts | access-svc | OAuth provider 연결 | users 1:N(FK `ON DELETE CASCADE`), `(provider, provider_user_id)`. OAuth 가입 때 만들고, 로그인한 사용자가 설정에서 다른 provider를 연동·해제한다(사용자당 provider 하나, 가입 provider는 해제 불가 — 앱에서 검사) |
| user_refresh_tokens | access-svc | JWT refresh token | `token_hash`(SHA-256), `revoked_at`으로 탈취 감지, `user_agent`(세션 목록의 기기 구분. 컬럼 신설 이전 발급분은 NULL) |
| user_mfa | access-svc | TOTP 설정 | PK/FK `user_id`(삭제 cascade), `secret_cipher`·`secret_nonce`(AES-GCM. 검증에 원문이 필요해 해시로 둘 수 없다), `activated_at`(NULL이면 등록만 하고 미활성이라 로그인을 막지 않음), `last_used_counter`(같은 시간 창 재사용 차단) |
| user_mfa_recovery_codes | access-svc | 1회용 복구 코드 | `code_hash`(SHA-256, 원문 미저장), `consumed_at`. 미소비분에 partial index |
| user_mfa_challenges | access-svc | 로그인 2단계 중간 상태 | `token_hash` UK, `expires_at`(기본 300초), `consumed_at`. 비밀번호는 통과했지만 코드를 아직 못 받은 상태다 |
| workspaces | access-svc | 격리 단위 | 문서·Wiki·채팅의 소속 기준, 아이콘 `icon_emoji`·`icon_image_hash`·`icon_image_content_type`(이모지와 이미지는 CHECK 제약으로 배타), workspace 설정 snapshot인 `ingest_lint_provider`·`ingest_lint_model`(새 workspace 기본값 `gemini/gemini-3.1-flash-lite`) |
| workspace_icons | access-svc | 아이콘 이미지 바이너리 | PK/FK `workspace_id` → `workspaces(id)`(삭제 cascade), `image bytea`. 목록 조회가 바이너리를 함께 읽지 않도록 workspaces에서 분리했다. 1MB 상한이라 object storage를 쓰지 않는다 |
| workspace_members | access-svc | 멤버십(N:M 대비) | 복합 PK `(workspace_id, user_id)`, `role`(owner/member) |
| workspace_membership_periods | access-svc | 멤버였던 기간 이력(탈퇴·제거 후에도 보존) | PK `(workspace_id, user_id, joined_at)`, `left_at` NULL이면 현재 멤버. `workspace_members` INSERT·DELETE trigger가 기록, FK 없음; V21 |
| workspace_invitations | access-svc | 이메일 초대(수락 전 상태) | `token_hash`(SHA-256, 원문 미저장), `expires_at`, `accepted_at`/`accepted_by`/`revoked_at`. 대기 중 초대는 `(workspace_id, email)` partial unique라 재초대는 새 행이 아니라 재발송이다. 계정이 `(email, provider)`로 분리돼 있어 어느 계정이 멤버가 될지는 수락 시점에 정해진다 |
| data_purge_requests | access-svc | 회원 탈퇴 뒤 document에 요청할 데이터 파기 | PK `(kind, target_id)`, `kind`는 `user`(공유 워크스페이스의 개인 데이터)·`workspace`(혼자 쓰던 워크스페이스). `attempts`·`next_attempt_at`·`last_error`로 재시도. 계정이 지워진 뒤에도 남아야 해 FK 없음; V22 |
| user_consents | access-svc | 가입·재동의 이력(덮어쓰지 않고 쌓음) | users FK `ON DELETE CASCADE`, `terms_version`·`privacy_version`(설정 `app.legal.*`), `age_confirmed`(만 18세 이상), `marketing_opt_in`(선택), `consented_at`. 최근 행의 이용약관 버전이 현재와 다르면 재동의 대상; V23 |

### 보관 기간과 정리

`ExpiredRecordCleanupJob`이 하루 1회(`app.cleanup.delay-ms`) 기한이 지난 행을 지운다. 기간은 `app.cleanup.*` 설정값이다.

| 대상 | 지우는 시점 |
|---|---|
| email_verifications | 코드·인증 토큰 중 늦은 만료 시각 + 7일 |
| user_refresh_tokens | 만료 또는 폐기 + 7일 |
| workspace_invitations | 수락·취소·만료 중 가장 이른 시각 + 30일 |
| idempotency_records | `expires_at`(응답 보관 24시간)이 지나면 |
| workspaces (휴지통) | `deleted_at` + 30일. document 내부 API `POST /internal/purge/workspaces`로 문서·채팅·회의·파일을 먼저 지운 뒤 행을 지운다. 멤버십·초대·아이콘은 CASCADE로 지워지고 `workspace_membership_periods`에는 `left_at`이 남는다. document 호출이 실패하면 행을 남겨 다음 실행에서 다시 시도한다 |

회원 탈퇴는 계정을 지우는 트랜잭션에서 `data_purge_requests`를 남기고, `DataPurgeRequestJob`이 1분마다 document 파기를 호출한다. 성공하면 요청을 지우고(워크스페이스는 행도 지운다), 실패하면 1분부터 두 배씩 늘려 최대 6시간 간격으로 다시 시도한다.

기록 정리는 조건부 DELETE라 여러 replica가 동시에 돌아도 결과가 같다. 워크스페이스는 행을 `FOR UPDATE SKIP LOCKED`로 잡고 처리해 한 replica만 document를 호출한다.
