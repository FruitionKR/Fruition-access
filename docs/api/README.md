# access-svc API

[서비스 문서](../README.md)

인증과 워크스페이스를 관리한다. 로컬 base URL은 `http://localhost:8081`이다.

| 도메인 | API 수 | 역할 | 호출 연결 |
|---|---:|---|---|
| [Auth](auth.md) | 31 | 가입·인증·로그인·토큰·프로필·화면 언어·프로필 사진·소셜 계정 연동·가입 동의·회원 탈퇴 관리 | 20개는 frontend, 11개(화면 언어 저장 포함)(소셜 계정 연동, 소셜 가입 동의, 약관 재동의, 회원 탈퇴, 프로필 사진, 인증번호 비밀번호 변경)는 호출자 미확인(frontend 구현 예정). signup과 회원 탈퇴가 document-svc를 호출 |
| [Users](users.md) | 1 | 다른 사용자의 프로필 사진 조회 | 호출자 미확인(frontend 구현 예정) |
| [Workspaces](workspaces.md) | 16 | 워크스페이스·멤버 관리와 내부 인가·AI 모델 설정 | 9개는 frontend, 4개(`/internal/**`)는 document-svc·ai-svc, 3개는 호출자 미확인 |
| [Invitations](invitations.md) | 5 | 이메일 초대 발송·취소·수락 | 3개는 frontend, 2개는 호출자 미확인 |
| [MCP internal authorization](mcp.md) | 1 | grant·workspace/client 정책과 현재 원본 인가 판정 | document-svc MCP resource server가 호출 예정 |

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
| `PATCH /api/auth/me/locale` | **호출자 미확인** (frontend 디스플레이 설정 언어 선택 구현 중) | 없음 |
| `DELETE /api/auth/me` | **호출자 미확인** (frontend 탈퇴 화면 구현 예정) | document-svc `purge/users`, `purge/workspaces` 다음 ai-svc `ai/purge/users`, `ai/purge/workspaces`(`DataPurgeRequestJob`이 커밋 뒤 호출) |
| `POST /api/auth/me/consents` | **호출자 미확인** (frontend 재동의 화면 구현 예정) | 없음 |
| `POST /api/auth/oauth/signup/consent` | **호출자 미확인** (frontend 가입 동의 화면 구현 예정) | document-svc `initial-note`(기본 워크스페이스 생성) |
| `PUT /api/auth/me/email` | frontend `src/entities/user/api/account.ts:29` | 없음 |
| `PUT /api/auth/me/password` | frontend `src/entities/user/api/account.ts:20` | 없음 |
| `POST /api/auth/me/password/verification` | **호출자 미확인** (frontend 로그인 상태 비밀번호 변경 전환 예정) | 없음 |
| `PUT /api/auth/me/password/verified` | **호출자 미확인** (frontend 로그인 상태 비밀번호 변경 전환 예정) | 없음 |
| `GET /api/auth/me/sessions` | frontend `src/entities/user/api/sessions.ts:14` | 없음 |
| `DELETE /api/auth/me/sessions/{session_id}` | frontend `src/entities/user/api/sessions.ts:20` | 없음 |
| `GET /api/auth/me/mfa` | frontend `src/entities/user/api/mfa.ts:18` | 없음 |
| `POST /api/auth/me/mfa` | frontend `src/entities/user/api/mfa.ts:23` | 없음 |
| `POST /api/auth/me/mfa/activate` | frontend `src/entities/user/api/mfa.ts:28` | 없음 |
| `DELETE /api/auth/me/mfa` | frontend `src/entities/user/api/mfa.ts:35` | 없음 |
| `POST /api/auth/me/oauth-accounts/{provider}/link` | **호출자 미확인** (frontend 구현 예정, FruitionKR/Fruition-frontend#105) | 없음 |
| `POST /api/auth/me/oauth-accounts/link/confirm` | **호출자 미확인** (frontend 구현 예정, FruitionKR/Fruition-frontend#105) | 없음 |
| `DELETE /api/auth/me/oauth-accounts/{provider}` | **호출자 미확인** (frontend 구현 예정, FruitionKR/Fruition-frontend#105) | 없음 |
| `PUT /api/auth/me/avatar` | **호출자 미확인** (frontend 프로필 설정 구현 예정, FruitionKR/Fruition-access#25) | 없음 |
| `DELETE /api/auth/me/avatar` | **호출자 미확인** (frontend 프로필 설정 구현 예정, FruitionKR/Fruition-access#25) | 없음 |

### Users

| API | 호출자 | 하위 호출 |
|---|---|---|
| `GET /api/users/{user_id}/avatar` | **호출자 미확인** (frontend 레일·설정·멤버 목록 구현 예정, FruitionKR/Fruition-access#25) | 없음 |

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
| `GET /internal/workspaces/{workspace_id}/member-users` | document-svc AI 사용량 정산(Fruition-document#59) | 없음 |
| `GET /internal/workspaces/{workspace_id}/settings` | document-svc 업로드 자동 ingest 판단(ADR 0019, 호출자 구현 예정) | 없음 |
| `GET /internal/workspaces/{workspace_id}/ai-model-settings` | document-svc `src/main/java/fruition/core/authz/WorkspaceAiModelClient.java:25` | 없음 |
| `PUT /internal/workspaces/{workspace_id}/ai-model-settings` | document-svc `src/main/java/fruition/core/authz/WorkspaceAiModelClient.java:36` | 없음 |

### MCP internal authorization

| API | 호출자 | 하위 호출 |
|---|---|---|
| `POST /internal/mcp/grants/{grant_id}/authorize` | document-svc MCP resource server 구현 예정 | PostgreSQL의 grant, workspace/client policy, 현재 membership/permissions |

### Invitations

| API | 호출자 | 하위 호출 |
|---|---|---|
| `POST /api/workspaces/{workspace_id}/invitations` | frontend `src/entities/workspace/api/members.ts:34` | 없음 |
| `GET /api/workspaces/{workspace_id}/invitations` | **호출자 미확인** | 없음 |
| `DELETE /api/workspaces/{workspace_id}/invitations/{invitation_id}` | **호출자 미확인** | 없음 |
| `GET /api/invitations/{token}` | frontend `src/entities/workspace/api/invitations.ts:14` | 없음 |
| `POST /api/invitations/{token}/accept` | frontend `src/entities/workspace/api/invitations.ts:19` | 없음 |

### 오류 문구와 메일 언어

- 오류 응답 `error.message`와 검증 실패 `error.details[].reason`은 요청의 `Accept-Language`로 언어를 고른다.
  `ko`·`en`(지역 표기 `en-US` 등 포함)만 받고, 헤더가 없거나 그 밖의 언어면 한국어다. 헤더가 없는 기존 클라이언트는
  예전과 같은 한국어 문구를 받는다. 분기는 언어와 상관없는 `error.code`로 한다.
- 문구는 `src/main/resources/i18n/messages.properties`(한국어)와 `messages_en.properties`(영어)에 있다.
  `/internal/**`의 오류 문구는 서비스 사이 호출이라 한국어로 둔다.
- access가 보내는 메일(인증번호, 워크스페이스 초대, 이메일 변경 알림)은 수신자 계정의 저장 언어(`users.locale`) →
  요청의 `Accept-Language` → 한국어 순으로 언어를 고른다. 가입 전 인증 메일이나 새 이메일 주소처럼 수신자 계정이 없으면
  요청 언어를 쓰고, 초대받는 사람이 기존 사용자가 아니면 초대한 사람의 요청 언어를 쓴다. 템플릿은
  `i18n/mail.properties`·`mail_en.properties`다. 결정 배경은 [ADR-0020](../adr/0020-localized-errors-and-mail.md).

### 내부 호출 인증

`/internal/**`은 `SecurityConfig`에서 `permitAll`이라 사용자 JWT를 요구하지 않는다. MCP 경로
`/internal/mcp/**`는 같은 security chain의 전용 필터가 MVC body parsing 전에 `X-Internal-Token`을
상수 시간 비교하고, 나머지 기존 내부 경로는 각 컨트롤러가 검증한다
(`src/main/java/fruition/access/security/SecurityConfig.java`,
`src/main/java/fruition/access/workspace/controller/InternalAuthzController.java:69`,
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
→ `src/main/java/fruition/access/user/service/OAuthUserService.java:157`) 경로에서도 발생한다.
OAuth 로그인 콜백은 Spring Security가 처리하는 경로라 이 문서의 API 목록에는 없다.
로그인한 사용자의 소셜 계정 연동(`/oauth2/authorization/{provider}?mode=link&link_token=...`)도 같은
콜백을 타지만, 계정을 만들지 않으므로 기본 워크스페이스 생성과 하위 호출이 없다. 콜백은 로그인용 `?code=` 대신
`?link_code=`(실패 시 `?link=failed`)를 넘기고, 연결은 `POST /api/auth/me/oauth-accounts/link/confirm`에서 일어난다.
`POST /api/auth/oauth/exchange`는 발급된 code를 토큰으로 교환할 뿐 하위 호출이 없다.
