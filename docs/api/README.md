# access-svc API

[서비스 문서](../README.md)

인증과 워크스페이스를 관리한다. 로컬 base URL은 `http://localhost:8081`이다.

| 도메인 | API 수 | 역할 | 호출 연결 |
|---|---:|---|---|
| [Auth](auth.md) | 20 | 가입·인증·로그인·토큰·프로필 관리 | 20개 모두 frontend가 호출. signup만 document-svc를 호출 |
| [Workspaces](workspaces.md) | 16 | 워크스페이스·멤버 관리와 내부 인가·AI 모델 설정 | 9개는 frontend, 4개(`/internal/**`)는 document-svc·ai-svc, 3개는 호출자 미확인 |
| [Invitations](invitations.md) | 5 | 이메일 초대 발송·취소·수락 | 3개는 frontend, 2개는 호출자 미확인 |

## 호출 연결 요약

각 API의 호출자와 하위 호출을 한 표로 모았다. 같은 내용이 API별 **10. 구현 파일** 항목에
`호출자`·`하위 호출` 줄로도 들어 있다.

- `frontend`는 Fruition-frontend, `document-svc`는 Fruition-document, `ai-svc`는 Fruition-ai다.
- **호출자 미확인**은 세 저장소의 코드에서 호출 지점을 찾지 못했다는 뜻이다. 호출자가 없다고
  단정한 것이 아니라, 근거를 찾지 못했다는 기록이다.
- frontend 측 근거는 과소 보고될 수 있다. 경로를 `workspacePath(workspaceId, ...segments)`
  같은 헬퍼와 템플릿 문자열로 조립하므로, 문자열 그대로 검색하면 호출 지점이 누락된다.
  이 표는 `src/shared/api/client.ts`의 헬퍼를 전개해 확인한 결과다.
- access-svc가 밖으로 부르는 API는 `POST /internal/workspaces/{workspaceId}/initial-note`
  하나뿐이다(`DocumentInternalClient`, `app.internal.document-base-url`).

### Auth

| API | 호출자 | 하위 호출 |
|---|---|---|
| `POST /api/auth/email-availability` | frontend `src/entities/user/api/emailVerification.ts:32` | 없음 |
| `POST /api/auth/email-verifications` | frontend `src/entities/user/api/emailVerification.ts:22` | 없음 |
| `POST /api/auth/email-verifications/{verification_id}/confirm` | frontend `src/entities/user/api/emailVerification.ts:46` | 없음 |
| `POST /api/auth/signup` | frontend `src/entities/user/api/emailVerification.ts:63` | document-svc `initial-note` |
| `POST /api/auth/login` | frontend `src/entities/user/api/login.ts:16` | 없음 |
| `POST /api/auth/login/mfa` | frontend `src/entities/user/api/mfa.ts:5` | 없음 |
| `POST /api/auth/refresh` | frontend `src/shared/api/client.ts:57` | 없음 |
| `POST /api/auth/logout` | frontend `src/entities/user/api/login.ts:11` | 없음 |
| `POST /api/auth/oauth/exchange` | frontend `src/entities/user/api/login.ts:32` | 없음 |
| `POST /api/auth/password-reset` | frontend `src/entities/user/api/emailVerification.ts:82` | 없음 |
| `GET /api/auth/me` | frontend `src/entities/user/api/account.ts:6` | 없음 |
| `PATCH /api/auth/me` | frontend `src/entities/user/api/account.ts:11` | 없음 |
| `PUT /api/auth/me/email` | frontend `src/entities/user/api/account.ts:29` | 없음 |
| `PUT /api/auth/me/password` | frontend `src/entities/user/api/account.ts:20` | 없음 |
| `GET /api/auth/me/sessions` | frontend `src/entities/user/api/sessions.ts:14` | 없음 |
| `DELETE /api/auth/me/sessions/{session_id}` | frontend `src/entities/user/api/sessions.ts:20` | 없음 |
| `GET /api/auth/me/mfa` | frontend `src/entities/user/api/mfa.ts:18` | 없음 |
| `POST /api/auth/me/mfa` | frontend `src/entities/user/api/mfa.ts:23` | 없음 |
| `POST /api/auth/me/mfa/activate` | frontend `src/entities/user/api/mfa.ts:28` | 없음 |
| `DELETE /api/auth/me/mfa` | frontend `src/entities/user/api/mfa.ts:35` | 없음 |

### Workspaces

| API | 호출자 | 하위 호출 |
|---|---|---|
| `GET /api/workspaces` | frontend `src/entities/workspace/api/workspace.ts:5` | 없음 |
| `POST /api/workspaces` | frontend `src/entities/workspace/api/workspace.ts:19` | document-svc `initial-note` |
| `GET /api/workspaces/trash` | **호출자 미확인** | 없음 |
| `PATCH /api/workspaces/{workspace_id}` | frontend `src/entities/workspace/api/workspace.ts:10` | 없음 |
| `DELETE /api/workspaces/{workspace_id}` | **호출자 미확인** | 없음 |
| `POST /api/workspaces/{workspace_id}/restore` | **호출자 미확인** | 없음 |
| `PUT /api/workspaces/{workspace_id}/icon` | frontend `src/entities/workspace/api/workspace.ts:28` | 없음 |
| `PUT /api/workspaces/{workspace_id}/icon/image` | frontend `src/entities/workspace/api/workspace.ts:39` | 없음 |
| `GET /api/workspaces/{workspace_id}/icon/image` | frontend `src/entities/workspace/api/workspace.ts:44` | 없음 |
| `GET /api/workspaces/{workspace_id}/members` | frontend `src/entities/workspace/api/members.ts:14` | 없음 |
| `PATCH /api/workspaces/{workspace_id}/members/{user_id}` | frontend `src/entities/workspace/api/members.ts:20` | 없음 |
| `DELETE /api/workspaces/{workspace_id}/members/{user_id}` | frontend `src/entities/workspace/api/members.ts:29` | 없음 |
| `GET /internal/authz/workspaces/{workspace_id}/users/{user_id}` | document-svc `src/main/java/fruition/core/authz/WorkspaceAccessGuard.java:95`, ai-svc `pipeline/app/modules/skill/infrastructure/workspace_authorization.py:19` | 없음 |
| `GET /internal/users/{user_id}` | document-svc `src/main/java/fruition/core/authz/AccessUserClient.java:58` | 없음 |
| `GET /internal/workspaces/{workspace_id}/ai-model-settings` | document-svc `src/main/java/fruition/core/authz/WorkspaceAiModelClient.java:25` | 없음 |
| `PUT /internal/workspaces/{workspace_id}/ai-model-settings` | document-svc `src/main/java/fruition/core/authz/WorkspaceAiModelClient.java:36` | 없음 |

### Invitations

| API | 호출자 | 하위 호출 |
|---|---|---|
| `POST /api/workspaces/{workspace_id}/invitations` | frontend `src/entities/workspace/api/members.ts:34` | 없음 |
| `GET /api/workspaces/{workspace_id}/invitations` | **호출자 미확인** | 없음 |
| `DELETE /api/workspaces/{workspace_id}/invitations/{invitation_id}` | **호출자 미확인** | 없음 |
| `GET /api/invitations/{token}` | frontend `src/entities/workspace/api/invitations.ts:14` | 없음 |
| `POST /api/invitations/{token}/accept` | frontend `src/entities/workspace/api/invitations.ts:19` | 없음 |

### 내부 호출 인증

`/internal/**`은 `SecurityConfig`에서 `permitAll`이라 JWT 필터를 통과시키지 않고
(`src/main/java/fruition/access/security/SecurityConfig.java:97`), 각 컨트롤러가 `X-Internal-Token`
헤더를 상수 시간 비교로 검증한다
(`src/main/java/fruition/access/workspace/controller/InternalAuthzController.java:69`,
`src/main/java/fruition/access/workspace/controller/InternalWorkspaceAiModelController.java:56`).
토큰 값은 `app.internal.callback-token`(`INTERNAL_CALLBACK_TOKEN`)이다.
access-svc의 내부 엔드포인트에는 `X-Agent-Service-Token`을 쓰지 않는다 — 그 헤더는
ai-svc가 document-svc Tool 경로를 부를 때 쓰는 별개 헤더다.

access-svc가 document-svc로 나갈 때도 같은 `X-Internal-Token`을 붙인다
(`src/main/java/fruition/access/workspace/service/DocumentInternalClient.java:52`).

### 문서 대상이 아닌 호출 경로

`POST /internal/workspaces/{workspaceId}/initial-note` 호출은 기본 워크스페이스 생성
(`WorkspaceService.createDefault`)을 통해서도 일어난다. 즉 `POST /api/auth/signup`과
OAuth 로그인 콜백(`/login/oauth2/**` → `CustomOAuth2UserService`
→ `src/main/java/fruition/access/user/service/OAuthUserService.java:70`) 경로에서도 발생한다.
OAuth 로그인 콜백은 Spring Security가 처리하는 경로라 이 문서의 API 목록에는 없다.
`POST /api/auth/oauth/exchange`는 발급된 code를 토큰으로 교환할 뿐 하위 호출이 없다.
