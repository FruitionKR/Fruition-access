# ADR-0019: 멤버별 권한 항목과 업로드 자동 ingest 설정

- 상태: 적용됨
- 관련: Fruition-ai ADR 0033(워크스페이스 공유 wiki), V25 `add_member_permissions_and_auto_ingest`, [workspaces API](../api/workspaces.md), [data-model](../data-model.md)

## 맥락

Fruition-ai ADR 0033으로 wiki는 개인 자산이 아니라 워크스페이스 공유 자산이 됐다. ingest는 모두가 보는 wiki를
바꾸고 LLM 비용을 쓰므로, 멤버라면 누구나 실행할 수 있는 지금 구조로는 의도치 않은 변경과 비용을 막을 수 없다.
지금 access의 인가 단위는 역할(OWNER/MEMBER) 하나뿐이다. 또 파일 업로드 때 자동으로 ingest할지는 워크스페이스
단위의 비용 결정인데 둘 곳이 없다.

## 결정

1. 권한 항목을 별도 테이블 `workspace_member_permissions(workspace_id, user_id, permission)`에 둔다.
   PK는 세 컬럼 전부, `workspace_members`로 복합 FK(`ON DELETE CASCADE`), CHECK `permission IN ('wiki.ingest')`.
   멤버를 제거하면 권한도 사라지고 재초대하면 권한 없이 시작한다. JPA는 `WorkspaceMember`의
   `@ElementCollection`과 변환기로 매핑하며 API·DB·Redis 표기는 `"wiki.ingest"`다.
2. OWNER는 항상 모든 권한을 가진다. OWNER 행은 저장하지 않고 응답을 만들 때 전부로 계산한다.
   OWNER의 권한은 바꿀 수 없다(`400 OWNER_PERMISSIONS_FIXED`, 호출자 본인 포함).
3. `PATCH /api/workspaces/{ws}/members/{user}` 본문을 `{role?, permissions?}`로 확장한다. 하나는 필요하고
   `permissions`는 전체 집합을 교체한다. 권한 항목은 기능마다 OWNER가 부여하며 역할과 독립이다. 역할을 바꿔도
   부여된 권한은 그대로이고, 강등된 멤버는 부여받은 항목만 가진다. 실제로 바뀔 때만 인가 projection을 무효화한다.
   새 멤버(초대 수락, OWNER 초대, 워크스페이스 생성자)는 저장된 권한 없이 시작한다.
4. `workspaces.auto_ingest_on_upload boolean NOT NULL DEFAULT false`를 추가하고 기존
   `PATCH /api/workspaces/{ws}`가 `{name?, auto_ingest_on_upload?}`를 받는다. 비용이 드는 동작이라 기본은 끈다.
5. document용 내부 계약: 인가 조회는 `{"role", "permissions"}`를 돌려주고(`role`은 그대로, NONE은 `[]`,
   OWNER는 전부), `GET /internal/workspaces/{ws}/settings`가 `{"auto_ingest_on_upload"}`를 돌려준다
   (없거나 삭제되면 404). Redis 키 `authz:role:{ws}:{uid}`는 그대로이며 캐시 값의 형식은 document가 소유한다.
6. V25는 배포 당일 기존 MEMBER가 갑자기 ingest를 못 하지 않도록 모든 기존 MEMBER에 `wiki.ingest`를 부여한다.

## 대안과 기각 사유

- 역할 변경에 권한을 연동(승격 시 비우고 강등 시 전부 부여): 역할과 권한이 서로를 바꿔 규칙이 숨는다. 권한은 기능마다 OWNER가 명시적으로 부여하는 편이 예측 가능하다.
- 역할 추가(EDITOR 등): 항목이 늘 때마다 역할 조합이 폭발하고 기존 `OWNER`/`MEMBER` 판정 코드 전반에 영향을 준다.
- 워크스페이스 전체 토글: 사람별 통제가 안 된다. 비용을 쓰는 사람만 골라 허용할 수 없다.
- 승인 흐름(요청 후 OWNER 승인): 지금 단계에는 과하다. 상태·알림·만료 설계가 필요하다.
- `text[]` 컬럼 또는 항목별 boolean 컬럼: 항목을 추가할 때마다 스키마나 배열 검증을 바꿔야 하고, FK cascade와
  CHECK로 무결성을 보장하기 어렵다. 행 단위 테이블이 항목 추가를 CHECK 변경 하나로 끝낸다.

## 결과

- 항목을 늘릴 때는 enum과 CHECK 제약, 기존 멤버 부여 여부를 함께 정한다.
- document는 `permissions`로 ingest를 인가하고 `auto_ingest_on_upload`로 자동 수집을 결정한다(호출자 구현은 별도).
  document가 캐시한 값은 access의 evict 또는 TTL로 갱신된다.
- `LAST_OWNER` 규칙은 그대로다. OWNER를 강등하면 부여받은 권한 항목이 없는 한 편입할 수 없으므로, 필요하면 OWNER가 따로 부여한다.
