# MCP internal authorization API

[서비스 문서](../README.md) / [access-svc](README.md)

I1의 access 소유 grant·workspace/client 정책과 현재 원본 인가 판정 계약이다. 이 단계에는 공개 grant 발급,
OAuth metadata/token/consent, 연결 관리 UI가 없다.

## `POST /internal/mcp/grants/{grant_id}/authorize`

- 인증: `X-Internal-Token` 필수. 사용자 JWT나 MCP bearer token을 이 endpoint로 전달하지 않는다.
- `grant_id`: 서버가 만든 UUIDv4.
- unknown JSON field, non-string ID/enum/scope/permission, duplicate collection, 잘못된
  phase/execution basis 조합은 `400`이다.
- 정상적으로 판정 가능한 요청은 허용/거절 모두 `200`이며 `decision`을 확인한다.
- 저장소 오류는 `DENY / AUTHZ_UNAVAILABLE`이다. 이전 허용 결과나 Redis role projection으로 대체하지 않는다.

```json
{
  "token_context": {
    "subject": "user-1",
    "workspace_id": "ws_1",
    "client_id": "https://client.example/metadata",
    "policy_generation": 2,
    "scopes": ["read"]
  },
  "action": {
    "phase": "TOOL_CALL",
    "required_scopes": ["read"],
    "required_permissions": [],
    "execution_basis": "ACTIVE_GRANT",
    "approved_policy_generation": null
  }
}
```

```json
{
  "decision": "ALLOW",
  "deny_reason": null,
  "grant": {
    "grant_id": "00000000-0000-4000-8000-000000000001",
    "status": "ACTIVE",
    "revocation_reason": null,
    "user_id": "user-1",
    "workspace_id": "ws_1",
    "client_id": "https://client.example/metadata",
    "scopes": ["read"]
  },
  "policy": {
    "enabled": true,
    "generation": 2,
    "allowed_scopes": ["read"]
  },
  "current_access": {
    "role": "MEMBER",
    "permissions": []
  },
  "effective_scopes": ["read"],
  "client_name": null,
  "checked_at": "2026-11-07T12:00:00Z"
}
```

`WEB_APPROVAL`의 ALLOW 응답만 현재 허용된 `client_id`의 CIMD를 서버가 다시 검증해 얻은 nonblank
`client_name`을 싣는다. caller가 보낸 이름이나 과거 화면 값을 사용하지 않는다. metadata 조회/검증 또는 이름이
없으면 `DENY / AUTHZ_UNAVAILABLE`이며, TOOL_CALL·OPERATION_BOUNDARY·READ_RECEIPT는 이 metadata fetch에 새로
의존하지 않고 `client_name=null`이다.

`phase`는 `TOOL_CALL | PROPOSAL_CREATE | WEB_APPROVAL | OPERATION_BOUNDARY | READ_RECEIPT`,
`execution_basis`는 `ACTIVE_GRANT | EXPLICIT_WEB_APPROVAL`이다. `EXPLICIT_WEB_APPROVAL`은
`OPERATION_BOUNDARY`와 양수 `approved_policy_generation`을 함께 요구한다.

실제 access token으로 호출하는 `TOOL_CALL`과 `READ_RECEIPT`는 token context scope가 저장 grant scope와
정확히 같아야 한다. Document가 저장 provenance로 만드는 `PROPOSAL_CREATE`, `WEB_APPROVAL`, 그리고
`EXPLICIT_WEB_APPROVAL`의 `OPERATION_BOUNDARY`는 non-empty context scope가 저장 grant scope의 부분집합일 수
있다. 이 예외는 user/workspace/client/grant generation 일치, 현재 policy·role·permission·effective scope 검사와
관리자/보안 회수 차단을 완화하지 않는다.

`PROPOSAL_CREATE`는 document edit proposal에만 한정된 phase가 아니다. `start_ingest`처럼 아직 승인되지 않은
지속성 wiki draft를 새로 만드는 service actor boundary도 이 phase를 쓴다. 이 경우 context/required scope는
`wiki_write`, required permission은 `wiki.ingest`, execution basis는 `ACTIVE_GRANT`, approved generation은 null이다.
resume/finalize, 일반 source read, 승인 뒤 실행은 이 mapping을 재사용하지 않는다.

Deny reason은 다음 닫힌 목록이다.

`TOKEN_CONTEXT_MISMATCH | GRANT_INACTIVE | POLICY_DISABLED | POLICY_GENERATION_MISMATCH | NOT_MEMBER |
PERMISSION_REVOKED | INSUFFICIENT_SCOPE | CLIENT_DISABLED | PURGED | AUTHZ_UNAVAILABLE`

존재하지 않는 grant와 token-context mismatch 응답은 저장 grant/policy/access identity를 싣지 않는다. 유효 scope는
token, 저장 grant, 현재 workspace allowed scopes의 교집합이다. 현재 membership과 `effectivePermissions()`를 매 요청
PostgreSQL 원본에서 읽는다.

사용자 연결 해제(`USER_DISCONNECT`) 예외는 이미 명시적으로 웹 승인한 작업의 operation boundary이고 승인 세대가
현재 세대와 같을 때만 적용한다. 관리자 회수, workspace 비활성화, client/security 회수, 세대 불일치는 예외 없이 막는다.

## OAuth browser authorization entry

MCP OAuth는 기본 비활성화다. 활성화하려면 Access의 issuer/resource/TTL 설정과 별도로
`app.mcp.oauth.frontend-authorization-uri` (`MCP_OAUTH_FRONTEND_AUTHORIZATION_URI`)를 명시해야 한다. 값은 정확히
`/mcp/authorize` 경로인 HTTPS URI여야 하며 로컬 개발에서는 `localhost`, `127.0.0.1`, `[::1]`의 HTTP만 허용한다.
userinfo, query, fragment는 허용하지 않는다. authorization metadata의 canonical endpoint는 계속 Access의
`{issuer}/oauth2/authorize`다.

Access는 bearer가 없고 `Accept`가 명시적으로 `text/html`인 `GET /oauth2/authorize`만 위 고정 frontend URI로
`302` handoff한다. 원래 query에서 `response_type`, `client_id`, `redirect_uri`, `scope`, `state`,
`code_challenge`, `code_challenge_method`, `resource`의 단일 값만 한 번 decode/encode해 전달한다. unknown/duplicate,
잘못된 percent/UTF-8 encoding, 1,024자를 넘는 state, 8,192 bytes를 넘는 원본 query 또는 4,096 bytes를 넘는 최종
`Location`을 가진 요청은 `400`이다. 최종 `Location` 상한은 다른 응답 header가 connector의 전체 header buffer에 들어갈 여유를
남긴다. `Authorization`, cookie, bearer와 `next`/`return`/`url`, request host/forwarded host는 전달하지 않는다.
JSON/default Accept와 유효하지 않은 bearer는 기존처럼 `401`이며 모든 handoff/error에는 `no-store`, frame deny 정책이
적용된다. 이 단계에서는 CIMD 조회, pending/grant/code 생성, 로그인 또는 동의 결정을 하지 않는다.

Frontend 구현 계약은 다음과 같다. 공개 `/mcp/authorize` 페이지는 기존 로그인 후 같은 원본 query만 guarded resume하고,
현재 메모리의 web bearer와 `credentials: include`로 같은-origin Access proxy의 `GET /oauth2/authorize`를 호출한다.
응답 body의 pending DTO만 렌더링하고 응답 header `X-CSRF-TOKEN`과 `SESSION` cookie를 유지한다. 결정은 같은 bearer,
cookie, `X-CSRF-TOKEN`으로 `POST /oauth2/authorize/{pending_id}`에 `decision`과 승인 시 `workspace_id`만 보낸다.
이동 대상은 성공 응답의 서버 계산 `redirect_uri`만 사용한다. Frontend route/proxy 배포와 실제 page 구현은 별도 작업이며,
이 Access handoff만으로 browser OAuth journey가 완료됐다고 보지 않는다.

## Workspace OWNER connection management

두 endpoint 모두 기존 web bearer가 필요하다. refresh cookie만 있거나 MCP audience token을 보내면 `401`이다. 현재 활성
workspace와 원본 membership row를 매 요청 잠그고 확인하며 현재 OWNER만 사용할 수 있다. 활성 MEMBER는 `403`, 비멤버나
없는/삭제된 workspace는 존재를 숨기기 위해 `404`다. workspace MCP policy가 비활성화되어도 관리할 수 있다.

### `GET /api/workspaces/{workspace_id}/mcp-connections?page=0&size=20`

- `page` 기본값은 `0`, `size` 기본값은 `20`, 최대값은 `100`이다. 잘못된 값은 `400`이다.
- DB에서 `workspace_id`를 먼저 제한하고 `created_at DESC, grant_id DESC`로 안정적으로 page한다.
- 응답 envelope는 `connections`, `page`, `size`, `has_next`다.
- 각 connection은 사용자 본인 목록의 여덟 필드(`grant_id`, `workspace_id`, `client_id`, `scopes`, `status`,
  `revocation_reason`, `created_at`, `revoked_at`)와 grant 소유자의 `user_id`만 포함한다. token/secret, client 검증 상태,
  표시 이름, last-used 값은 제공하지 않는다.

### `DELETE /api/workspaces/{workspace_id}/mcp-connections/{grant_id}`

- `grant_id`는 canonical UUIDv4여야 하며 형식 오류는 `400`이다.
- grant를 path의 exact workspace로 제한한 뒤 잠그므로 없는 grant와 다른 workspace의 grant는 같은 body-free `404`다.
- in-scope grant는 `ADMIN_GRANT_REVOKE`로 회수한다. 활성 grant와 `USER_DISCONNECT`는 관리자 회수로 강화하며 이미 같은
  수준으로 회수됐거나 SECURITY/PURGED가 더 강한 grant는 바꾸지 않고 모두 `204`다.
- transaction commit 뒤 현재 authorize는 관리자 회수 grant를 즉시 거절한다. 감사 로그는 actor/workspace/grant와
  `ADMIN_GRANT_REVOKE` action/result만 기록하며 요청 body나 query를 기록하지 않는다.

이 endpoint는 downstream proposal/draft 정리나 실행 중단을 구현하지 않는다. 그 lifecycle 처리는 별도 I5 경계다.

## Persistence lifecycle

- workspace policy의 최초 행은 `DISABLED`, generation `1`이다. enabled/allowed scopes 실제 변경만 generation을 올린다.
- workspace off→on은 두 번의 실제 변경이며, policy 세대 변경은 옛 grant를 terminal revoke해 되살리지 않는다.
- grant에는 TTL이 없다. credential expiry는 grant 상태를 만들지 않는다.
- grant revoke는 `USER < ADMIN < SECURITY < PURGED` 방향으로만 강화되고 재활성화되지 않는다.
- grant 생성은 client policy → user → workspace → membership → workspace policy 순으로 잠그고 insert까지
  유지한다. member 제거와 user/workspace 삭제는 이 잠금과 충돌한 뒤 생성된 grant까지 같은 트랜잭션에서
  disconnect/revoke/purge한다.
- 사용자/워크스페이스 실제 row 삭제 trigger가 grant를 `PURGED`로 바꾸고 grant row를 남긴다. workspace policy도
  이미 있으면 비활성 fence로 남는다. policy가 없던 workspace는 live workspace row 잠금과 삭제 후 owner 부재가
  재생성을 막는다. 그래서 기존 account deletion과 두 workspace hard-delete job의 지연 요청이 옛 grant를 다시
  활성으로 해석할 수 없다.
