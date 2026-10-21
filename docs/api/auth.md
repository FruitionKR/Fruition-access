# Auth API

[서비스 문서](../README.md) / [access-svc](README.md)

가입·이메일 인증·로그인·토큰 API다.

- API 수: 30
- 호출 연결: [access-svc 호출 연결 요약](README.md#호출-연결-요약) 참고
- 약관 동의 단계: [약관 동의 플래그](#약관-동의-플래그-applegalconsent-required) 참고

## 약관 동의 플래그 (`app.legal.consent-required`)

약관 문안이 확정되기 전에는 환경 변수 `LEGAL_CONSENT_REQUIRED=false`(설정 `app.legal.consent-required`, 기본 `true`)로
동의 단계를 끈다. 끈 동안에는 동의를 요구하지도, `user_consents`에 기록하지도 않는다. 받지 않은 만 18세 확인이나
확정되지 않은 문안에 대한 동의를 남기지 않기 위해서다.

| 항목 | `true`(기본, 현재 동작) | `false`(약관 확정 전) |
|---|---|---|
| `POST /api/auth/signup` | `age_confirmed: true`·현재 `terms_version` 필수, 없으면 `400 CONSENT_REQUIRED` | 동의 항목을 생략해도 가입된다 |
| 소셜 신규 가입 | 콜백 `?signup_token=` → 동의 화면 → `POST /api/auth/oauth/signup/consent`(동의 필수) | 같은 흐름. 콜백은 그대로 `?signup_token=`을 주고, 프론트는 동의 화면 없이 `{ "signup_token" }`(데스크톱이면 `code_verifier`도)만 보내 가입을 끝낸다 |
| 동의 기록(`user_consents`) | 가입·재동의 때 쌓는다 | 요청에 동의 항목이 있어도 쓰지 않는다 |
| 로그인 응답·`GET /api/auth/me`의 `consent_required` | 최근 동의가 현재 이용약관 버전이 아니면 `true` | 항상 `false` |
| `POST /api/auth/me/consents` | 동의 확인 후 기록, `204` | 검사·기록 없이 `204` |

플래그를 `true`로 되돌리면 꺼져 있던 동안 가입한 회원은 동의 기록이 없으므로 다음 로그인부터 `consent_required: true`가 되어
`POST /api/auth/me/consents`로 확정된 버전에 동의한다. 이때 `app.legal.terms-version`도 확정 문안 버전으로 맞춘다.

## API 목차

| API | 목적 |
|---|---|
| [`POST /api/auth/email-availability`](#summary-post-api-auth-email-availability) | 회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. 같은 이메일의 계정이 하나라도 있으면(이메일 가입이든 소셜 가입이든) `available: false`입니다. 같은 이메일로 소셜 가입한 계정이 있으면 `oauth_providers`로 알려, 그 계정으로 로그인한 뒤 설정에서 연동하도록 안내할 수 있게 합니다. 이 경우 인증번호 요청과 회원가입은 `409 EMAIL_REGISTERED_WITH_OAUTH`로 거절됩니다. |
| [`POST /api/auth/email-verifications`](#summary-post-api-auth-email-verifications) | 회원가입/비밀번호 재설정/이메일 변경을 위한 인증번호를 발급합니다. |
| [`POST /api/auth/email-verifications/{verification_id}/confirm`](#summary-post-api-auth-email-verifications-verification-id-confirm) | 인증번호를 검증하고 1회용 verification_token을 발급합니다. |
| [`POST /api/auth/login`](#summary-post-api-auth-login) | 이메일/비밀번호를 검증하고 access token과 HttpOnly refresh 쿠키를 발급합니다. MFA를 켠 사용자에게는 `mfa_required`를 돌려줍니다. |
| [`POST /api/auth/logout`](#summary-post-api-auth-logout) | HttpOnly refresh 쿠키를 폐기하고 제거합니다. |
| [`GET /api/auth/me`](#summary-get-api-auth-me) | access token으로 인증된 사용자의 프로필을 반환합니다. |
| [`PATCH /api/auth/me`](#summary-patch-api-auth-me) | 인증된 사용자의 표시 이름을 변경합니다. |
| [`DELETE /api/auth/me`](#summary-delete-api-auth-me) | 본인을 다시 확인한 뒤 계정을 지우고 데이터 파기를 요청합니다(회원 탈퇴). |
| [`PUT /api/auth/me/avatar`](#summary-put-api-auth-me-avatar) | 인증된 사용자의 프로필 사진을 올리거나 바꿉니다. PNG·JPEG·WebP·GIF, 1MB 이하. |
| [`DELETE /api/auth/me/avatar`](#summary-delete-api-auth-me-avatar) | 인증된 사용자의 프로필 사진을 지웁니다. |
| [`POST /api/auth/me/consents`](#summary-post-api-auth-me-consents) | 이용약관이 바뀐 뒤 기존 회원의 동의를 다시 받습니다. |
| [`PUT /api/auth/me/email`](#summary-put-api-auth-me-email) | 본인을 다시 확인하고 새 이메일로 받은 인증번호 토큰으로 계정 이메일을 바꿉니다. 기존 이메일로 변경 알림이 갑니다. |
| [`GET /api/auth/me/sessions`](#summary-get-api-auth-me-sessions) | 폐기되지 않은 로그인 세션을 반환합니다. |
| [`DELETE /api/auth/me/sessions/{session_id}`](#summary-delete-api-auth-me-sessions-session-id) | 지정한 세션의 refresh token을 폐기합니다. |
| [`PUT /api/auth/me/password`](#summary-put-api-auth-me-password) | 현재 비밀번호(MFA를 켰으면 `mfa_code`도)를 확인하고 새 비밀번호로 바꿉니다. |
| [`POST /api/auth/me/password/verification`](#summary-post-api-auth-me-password-verification) | 로그인한 사용자의 계정 이메일로 비밀번호 변경용 인증번호를 보냅니다. |
| [`PUT /api/auth/me/password/verified`](#summary-put-api-auth-me-password-verified) | 계정 이메일 인증번호 토큰(MFA를 켰으면 `mfa_code`도)으로 본인을 확인하고 비밀번호를 바꿉니다(현재 비밀번호 불필요). |
| [`POST /api/auth/login/mfa`](#summary-post-api-auth-login-mfa) | 다단계 인증 코드로 로그인을 마칩니다. |
| [`GET /api/auth/me/mfa`](#summary-get-api-auth-me-mfa) | 다단계 인증 상태를 반환합니다. |
| [`POST /api/auth/me/mfa`](#summary-post-api-auth-me-mfa) | secret과 복구 코드를 발급합니다(등록 1단계). |
| [`POST /api/auth/me/mfa/activate`](#summary-post-api-auth-me-mfa-activate) | 코드를 확인하고 다단계 인증을 켭니다(등록 2단계). |
| [`DELETE /api/auth/me/mfa`](#summary-delete-api-auth-me-mfa) | 코드로 본인을 확인한 뒤 다단계 인증을 해제합니다. |
| [`POST /api/auth/me/oauth-accounts/{provider}/link`](#summary-post-api-auth-me-oauth-accounts-provider-link) | 소셜 계정 연동을 시작할 1회용 연동 토큰을 발급합니다. |
| [`POST /api/auth/me/oauth-accounts/link/confirm`](#summary-post-api-auth-me-oauth-accounts-link-confirm) | 연동 콜백이 넘긴 `link_code`로 소셜 계정을 현재 계정에 연결합니다. |
| [`DELETE /api/auth/me/oauth-accounts/{provider}`](#summary-delete-api-auth-me-oauth-accounts-provider) | 연결된 소셜 계정의 연동을 해제합니다. |
| [`POST /api/auth/oauth/exchange`](#summary-post-api-auth-oauth-exchange) | OAuth code를 access token과 HttpOnly refresh 쿠키로 교환합니다. |
| [`POST /api/auth/oauth/signup/consent`](#summary-post-api-auth-oauth-signup-consent) | 소셜 신규 가입자의 만 18세 이상 확인과 약관 동의를 받아 계정을 만들고 로그인시킵니다. |
| [`POST /api/auth/password-reset`](#summary-post-api-auth-password-reset) | verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다. |
| [`POST /api/auth/refresh`](#summary-post-api-auth-refresh) | HttpOnly refresh 쿠키를 검증하고 access token과 refresh 쿠키를 회전합니다. |
| [`POST /api/auth/signup`](#summary-post-api-auth-signup) | 이메일/비밀번호로 신규 사용자를 생성합니다. |

## 한눈에 보기

<a id="summary-post-api-auth-email-availability"></a>
### `POST /api/auth/email-availability`

| 항목 | 내용 |
|---|---|
| 목적 | 회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. 같은 이메일의 계정이 하나라도 있으면(이메일 가입이든 소셜 가입이든) `available: false`입니다. 같은 이메일로 소셜 가입한 계정이 있으면 `oauth_providers`로 알려, 그 계정으로 로그인한 뒤 설정에서 연동하도록 안내할 수 있게 합니다. 이 경우 인증번호 요청과 회원가입은 `409 EMAIL_REGISTERED_WITH_OAUTH`로 거절됩니다. |
| 입력 | **Body** — `EmailAvailabilityRequest` |
| 출력 | `200` 가입 가능 여부 — `EmailAvailabilityResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다.<br>기존 인증번호 요청 API도 가입 이메일 중복을 `409`로 노출하므로 동일한 공개 범위를 유지한다.<br>그 밖의 조건은 상세 권한 규칙 참고 |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`429` 요청 횟수 제한 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-email-availability"></a>
### `POST /api/auth/email-availability` 상세

#### 1. Method + Path

`POST /api/auth/email-availability`

#### 2. 목적

회원가입 전에 이메일로 신규 가입할 수 있는지 빠르게 확인합니다. 같은 이메일의 계정이 하나라도 있으면(이메일 가입이든 소셜 가입이든) `available: false`입니다. 같은 이메일로 소셜 가입한 계정이 있으면 `oauth_providers`로 알려, 그 계정으로 로그인한 뒤 설정에서 연동하도록 안내할 수 있게 합니다. 이 경우 인증번호 요청과 회원가입은 `409 EMAIL_REGISTERED_WITH_OAUTH`로 거절됩니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음
- Content-Type: `application/json` (`EmailAvailabilityRequest`)

```json
{
  "email": "user@example.com"
}
```

#### 5. Response body

- HTTP `200`: 가입 가능 여부
- Content-Type: `*/*` (`EmailAvailabilityResponse`)
- `available`: 같은 이메일의 계정이 하나도 없으면 `true`. 이메일 가입(`local`) 계정이나 소셜 가입 계정이 있으면 `false`
- `oauth_providers`: 같은 이메일로 소셜 가입한 계정의 provider 목록(이름순). 없으면 빈 배열. 비어 있지 않으면 인증번호 요청과 회원가입이 `409 EMAIL_REGISTERED_WITH_OAUTH`로 거절되므로, 프론트는 인증 요청 전에 "그 소셜 계정으로 로그인한 뒤 설정 > 계정에서 연동"을 안내한다.

```json
{
  "available": false,
  "oauth_providers": [
    "google"
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 이메일 형식 | `ErrorResponse` |
| `429` | IP 또는 이메일 기준 요청 횟수 제한 초과 | `ErrorResponse` |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.
- 기존 인증번호 요청 API도 가입 이메일 중복을 `409`로 노출하므로 동일한 공개 범위를 유지한다.
- `oauth_providers`는 같은 이메일의 소셜 가입 여부와 provider까지 노출한다. 일반 회원가입 계정 존재를 이미 노출하는 것과 같은 범위로 보고, 같은 호출 제한을 적용한다. 인증번호 요청·회원가입의 `409 EMAIL_REGISTERED_WITH_OAUTH`도 같은 범위다.
- 계정 열거 비용을 제한하기 위해 Redis에서 IP당 30회/분, 이메일당 5회/분으로 호출을 제한한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/email-availability" \
  -H 'Content-Type: application/json' \
  --data '{"email":"user@example.com"}'
```

```json
{
  "available": false,
  "oauth_providers": [
    "google"
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: checkEmailAvailability`)
- 호출자: Fruition-frontend `src/entities/user/api/emailVerification.ts:32`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-email-availability)

</details>

<a id="summary-post-api-auth-email-verifications"></a>
### `POST /api/auth/email-verifications`

| 항목 | 내용 |
|---|---|
| 목적 | 회원가입/비밀번호 재설정을 위한 인증번호를 발급합니다. `purpose=signup`은 같은 이메일의 계정이 있으면 메일을 보내지 않고 `409`로 거절합니다. |
| 입력 | **Body** — `EmailVerificationRequest` |
| 출력 | `202` 인증번호 발급 — `EmailVerificationResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`409` 이미 가입된 이메일(purpose=signup): 이메일 가입 계정이면 `DUPLICATE_EMAIL` — `ErrorResponse`, 소셜 가입 계정만 있으면 `EMAIL_REGISTERED_WITH_OAUTH` — `EmailRegisteredWithOAuthResponse`(`oauth_providers` 포함)<br>`409` 이미 다른 계정이 쓰는 주소(purpose=email_change, 소셜 가입 포함): `DUPLICATE_EMAIL` — `ErrorResponse`<br>`429` 재요청 제한 초과(`VERIFICATION_RATE_LIMITED`, `Retry-After`) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-email-verifications"></a>
### `POST /api/auth/email-verifications` 상세

#### 1. Method + Path

`POST /api/auth/email-verifications`

#### 2. 목적

회원가입/비밀번호 재설정을 위한 인증번호를 발급합니다.

`purpose=signup`은 같은 이메일의 계정이 있으면 인증번호를 만들지도 메일을 보내지도 않고 `409`로 거절한다. 이메일 가입 계정이면 `DUPLICATE_EMAIL`, 소셜 가입 계정만 있으면 `EMAIL_REGISTERED_WITH_OAUTH`와 `oauth_providers`를 돌려주며, 프론트는 "그 소셜 계정으로 로그인한 뒤 설정 > 계정에서 연동"을 안내한다. 같은 이메일로 두 번째 계정이 생기면 이후 연동으로 합칠 수 없기 때문이다(#31). `purpose=email_change`도 그 주소를 쓰는 계정이 provider와 상관없이 하나라도 있으면 메일을 보내지 않고 `409 DUPLICATE_EMAIL`로 거절한다. 그 주소로는 [이메일 변경](#summary-put-api-auth-me-email)이 거절되기 때문이다. 인증 없이 부르는 API라 요청자 본인의 현재 주소도 같이 거절된다. 드러나는 정보는 `signup` 거절과 같은 범위다. `password_reset`은 존재 여부를 노출하지 않는다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`EmailVerificationRequest`)

```json
{
  "email": "user@example.com",
  "purpose": "signup"
}
```

#### 5. Response body

- HTTP `202`: 인증번호 발급
- Content-Type: `*/*` (`EmailVerificationResponse`)

```json
{
  "expires_in": 300,
  "retry_after": 60,
  "verification_id": "ev_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `409` | 이미 가입된 이메일(purpose=signup). `DUPLICATE_EMAIL`(이메일 가입 계정) | `ErrorResponse` |
| `409` | 같은 이메일의 소셜 가입 계정이 있음(purpose=signup). `EMAIL_REGISTERED_WITH_OAUTH` | `EmailRegisteredWithOAuthResponse` |
| `409` | 이미 다른 계정(소셜 가입 포함)이 쓰는 주소(purpose=email_change). `DUPLICATE_EMAIL` | `ErrorResponse` |
| `429` | 재요청 제한 초과(`VERIFICATION_RATE_LIMITED`). 아래 제한 중 하나를 넘음. `Retry-After` 헤더에 기다릴 초 | `ErrorResponse` |

요청 제한은 두 겹이다.

- 요청 수 제한: Redis에서 IP당 30회/분, 이메일당 5회/분으로 요청 자체를 센다(`Retry-After: 60`). 거절(`409`)된 요청도 센다.
  [`POST /api/auth/email-availability`](#summary-post-api-auth-email-availability)와 수치는 같지만 횟수는 따로 센다.
- 발급 제한: 같은 `(email, purpose)`의 재발송 쿨다운(60초)과 일일 한도(5회). 발급된 인증번호로 세므로 거절(`409`)된 요청은 세지 않는다.

```json
{
  "error": {
    "code": "EMAIL_REGISTERED_WITH_OAUTH",
    "message": "이미 소셜 계정(google)으로 가입된 이메일입니다. 그 계정으로 로그인한 뒤 설정에서 연동하세요: email=user@example.com"
  },
  "oauth_providers": [
    "google"
  ]
}
```

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/email-verifications" \
  -H 'Content-Type: application/json' \
  --data '{"email":"user@example.com","purpose":"signup"}'
```

```json
{
  "expires_in": 300,
  "retry_after": 60,
  "verification_id": "ev_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: requestEmailVerification`)
- 호출자: Fruition-frontend `src/entities/user/api/emailVerification.ts:22`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-email-verifications)

</details>

<a id="summary-post-api-auth-email-verifications-verification-id-confirm"></a>
### `POST /api/auth/email-verifications/{verification_id}/confirm`

| 항목 | 내용 |
|---|---|
| 목적 | 인증번호를 검증하고 1회용 verification_token을 발급합니다. |
| 입력 | **Path** — `verification_id`: `string`<br>**Body** — `VerificationConfirmRequest` |
| 출력 | `200` 검증 성공 — `VerificationConfirmResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 인증번호 불일치·만료·시도 초과 — `ErrorResponse`<br>`404` 인증 요청을 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-email-verifications-verification-id-confirm"></a>
### `POST /api/auth/email-verifications/{verification_id}/confirm` 상세

#### 1. Method + Path

`POST /api/auth/email-verifications/{verification_id}/confirm`

#### 2. 목적

인증번호를 검증하고 1회용 verification_token을 발급합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `verification_id` | `string` | 예 | - |

- Content-Type: `application/json` (`VerificationConfirmRequest`)

```json
{
  "code": "042173"
}
```

#### 5. Response body

- HTTP `200`: 검증 성공
- Content-Type: `*/*` (`VerificationConfirmResponse`)

```json
{
  "expires_in": 600,
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 인증번호 불일치·만료·시도 초과 | `ErrorResponse` |
| `404` | 인증 요청을 찾을 수 없음 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/email-verifications/<value>/confirm" \
  -H 'Content-Type: application/json' \
  --data '{"code":"042173"}'
```

```json
{
  "expires_in": 600,
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: confirmEmailVerification`)
- 호출자: Fruition-frontend `src/entities/user/api/emailVerification.ts:46`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-email-verifications-verification-id-confirm)

</details>

<a id="summary-post-api-auth-login"></a>
### `POST /api/auth/login`

| 항목 | 내용 |
|---|---|
| 목적 | 이메일/비밀번호를 검증하고 access token과 HttpOnly refresh 쿠키를 발급합니다. |
| 입력 | **Body** — `LoginRequest` |
| 출력 | `200` 로그인 성공 — `LoginResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `401` 이메일 또는 비밀번호 불일치 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-login"></a>
### `POST /api/auth/login` 상세

#### 1. Method + Path

`POST /api/auth/login`

#### 2. 목적

이메일/비밀번호를 검증하고 access token과 HttpOnly refresh 쿠키를 발급합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`LoginRequest`)

```json
{
  "email": "user@example.com",
  "password": "stringst"
}
```

#### 5. Response body

- HTTP `200`: 로그인 성공
- Content-Type: `*/*` (`LoginResponse`)

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

- 응답의 `Set-Cookie`가 `fruition_refresh_token`을 `HttpOnly; SameSite=Strict`로 저장한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 이메일 또는 비밀번호 불일치 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/login" \
  -H 'Content-Type: application/json' \
  -c cookies.txt \
  --data '{"email":"user@example.com","password":"stringst"}'
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: login`)
- 호출자: Fruition-frontend `src/entities/user/api/login.ts:16`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-login)

</details>

<a id="summary-post-api-auth-logout"></a>
### `POST /api/auth/logout`

| 항목 | 내용 |
|---|---|
| 목적 | HttpOnly refresh 쿠키를 폐기하고 제거합니다. |
| 입력 | **Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `204` 로그아웃 성공 |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | 없음 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-logout"></a>
### `POST /api/auth/logout` 상세

#### 1. Method + Path

`POST /api/auth/logout`

#### 2. 목적

HttpOnly refresh 쿠키를 폐기하고 제거합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Body: 없음
- Cookie: `fruition_refresh_token`(선택). 없거나 이미 만료돼도 로그아웃은 멱등하게 성공한다.

#### 5. Response body

- HTTP `204`: 로그아웃 성공
- Body: 없음

#### 6. Error response

- 없음

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/logout" \
  -b cookies.txt
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: logout`)
- 호출자: Fruition-frontend `src/entities/user/api/login.ts:11`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-logout)

</details>

<a id="summary-get-api-auth-me"></a>
### `GET /api/auth/me`

| 항목 | 내용 |
|---|---|
| 목적 | access token으로 인증된 사용자의 프로필을 반환합니다. |
| 입력 | 없음 |
| 출력 | `200` 조회 성공 — `MeResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다.<br>인증된 사용자만 호출할 수 있다. |
| 주요 오류 | `401` 인증되지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-auth-me"></a>
### `GET /api/auth/me` 상세

#### 1. Method + Path

`GET /api/auth/me`

#### 2. 목적

access token으로 인증된 사용자의 프로필을 반환합니다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

- 없음

- Body: 없음

#### 5. Response body

- HTTP `200`: 조회 성공
- Content-Type: `*/*` (`MeResponse`)
- `oauth_providers`: 로그인 수단으로 연결된 소셜 provider 목록(provider 이름순). OAuth로 가입한 provider도 포함하며, 연결이 없으면 빈 배열이다. `PATCH /api/auth/me`, `PUT /api/auth/me/email`, `PUT /api/auth/me/avatar`, `DELETE /api/auth/me/avatar`의 응답도 같은 `MeResponse`다.
- `avatar_url`: 프로필 사진 조회 경로(`GET /api/users/{user_id}/avatar`). 사진이 없으면 `null`이라 프론트는 이니셜 상자를 보여 준다. 사진의 SHA-256이 `?v=`로 붙어 사진을 바꾸면 URL도 바뀌므로, 예전 이미지가 브라우저 캐시에서 보이지 않는다.

```json
{
  "avatar_url": "/api/users/user_3f1c8a6b52d7411e9c04ab5d2e7f6081/avatar?v=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "oauth_providers": [
    "google"
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 인증되지 않음 | 없음(본문 없이 상태 코드만) |

인증 필터가 막는 401은 `HttpStatusEntryPoint`가 상태 코드만 내보내므로 본문이 없다. `error.code`로 분기할 수 없으니 상태 코드로 판정한다. 로그인 실패처럼 컨트롤러까지 도달한 뒤 발생하는 401은 `ErrorResponse`를 반환한다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 인증된 사용자만 호출할 수 있다.

#### 9. 예시 요청/응답

```bash
curl -X GET "$ACCESS/api/auth/me" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "avatar_url": "/api/users/user_3f1c8a6b52d7411e9c04ab5d2e7f6081/avatar?v=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "oauth_providers": [
    "google"
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: me`)
- 호출자: Fruition-frontend `src/entities/user/api/account.ts:6`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-get-api-auth-me)

</details>

<a id="summary-patch-api-auth-me"></a>
### `PATCH /api/auth/me`

| 항목 | 내용 |
|---|---|
| 목적 | 인증된 사용자의 표시 이름을 변경합니다. |
| 입력 | **Body** — `DisplayNameUpdateRequest` |
| 출력 | `200` 변경 성공 — `MeResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다. |
| 주요 오류 | `400` 잘못된 요청 — `ErrorResponse`<br>`401` 인증되지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`PATCH /api/auth/me`

#### 2. 목적

인증된 사용자의 표시 이름을 변경한다. 이메일과 provider는 이 API로 바꿀 수 없다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `display_name` | `string` | 예 | 새 표시 이름(255자 이하). 서버가 앞뒤 공백을 제거한다 |

```json
{
  "display_name": "새 이름"
}
```

#### 5. Response body

- HTTP `200`: 변경 성공 — `MeResponse`

```json
{
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "email": "user@example.com",
  "display_name": "새 이름",
  "avatar_url": null,
  "created_at": "2026-08-13T04:25:24.371948Z",
  "oauth_providers": [
    "google"
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `display_name`이 비었거나 255자를 넘음 | `INVALID_REQUEST` |
| `401` | access token이 없거나 유효하지 않음 | — |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다. 경로에 사용자 ID를 받지 않으므로 남의 프로필은 바꿀 수 없다.

#### 9. 예시 요청/응답

```bash
curl -X PATCH "$ACCESS/api/auth/me" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"display_name":"새 이름"}'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: updateDisplayName`)
- 호출자: Fruition-frontend `src/entities/user/api/account.ts:11`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-patch-api-auth-me)

</details>

<a id="summary-delete-api-auth-me"></a>
### `DELETE /api/auth/me`

| 항목 | 내용 |
|---|---|
| 목적 | 회원 탈퇴. 본인을 다시 확인한 뒤 계정을 지우고, 혼자 쓰던 워크스페이스와 document 데이터 파기를 요청합니다. |
| 입력 | **Header** — `Authorization: Bearer <access_token>`<br>**Body**(선택) — `AccountDeletionRequest` `{ "password", "mfa_code" }` |
| 출력 | `204` 탈퇴 완료. refresh 쿠키도 `Max-Age=0`으로 지운다 |
| 조건 | 비밀번호 계정은 `password`가 맞아야 한다.<br>비밀번호가 없는 소셜 계정은 10분 안에 로그인(OAuth code 교환·MFA 로그인 포함)해 받은 access token이어야 한다. refresh로 받은 토큰은 해당하지 않는다.<br>MFA를 켰으면 `mfa_code`도 맞아야 한다. |
| 주요 오류 | `401` 비밀번호·MFA 코드가 다름 / `REAUTHENTICATION_REQUIRED` 최근 로그인 기록 없음<br>`409` `SOLE_OWNER_OF_SHARED_WORKSPACE` 다른 멤버가 있는 워크스페이스의 유일한 OWNER<br>`429` 비밀번호 확인 시도 제한(비밀번호 변경과 같은 제한) |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-auth-me"></a>
### `DELETE /api/auth/me` 상세

#### 1. 처리

한 트랜잭션에서 다음을 한다.

1. 사용자가 속한 워크스페이스의 OWNER 행을 잠그고 멤버 구성을 읽는다(다른 OWNER의 동시 강등·탈퇴로 OWNER 없는 워크스페이스가 남지 않게 한다).
2. 활성 워크스페이스 중 다른 멤버가 있는데 OWNER가 본인뿐인 곳이 있으면 `409`로 거절한다. 먼저 다른 멤버를 OWNER로 올려야 한다.
3. 혼자 쓰던 워크스페이스와, 본인만 OWNER인 휴지통 워크스페이스는 파기 대상으로 정한다.
4. `data_purge_requests`에 사용자·워크스페이스 파기 요청을 넣는다.
5. refresh token을 지우고 `users` 행을 지운다. OAuth 연결·MFA·멤버십·멱등 기록은 CASCADE로 지워지고 멤버십 기간 이력에는 `left_at`이 남는다.

커밋 뒤 `DataPurgeRequestJob`(1분 주기)이 document `POST /internal/purge/users`, `POST /internal/purge/workspaces`를 호출하고,
성공하면 이어서 ai-svc `POST /internal/ai/purge/users`(`{"user_id"}`), `POST /internal/ai/purge/workspaces`(`{"workspace_ids"}`)를 호출한다.
둘 다 성공해야 요청을 지우고 워크스페이스 행을 지운다. 어느 한쪽이라도 1분부터 두 배씩 늘려 최대 6시간 간격으로 document부터 다시 시도하며(두 API 모두 멱등), 탈퇴 자체는 되돌리지 않는다.

탈퇴 직후 같은 이메일로 다시 가입할 수 있다. 지운 계정의 refresh token은 더 이상 쓸 수 없다.

#### 2. 409 응답

```json
{
  "error": { "code": "SOLE_OWNER_OF_SHARED_WORKSPACE", "message": "다른 멤버가 있는 워크스페이스의 OWNER를 먼저 넘겨 주세요." },
  "workspaces": [ { "id": "ws_9d47a0e9a6324341b47562553b75f92a", "name": "디자인팀" } ]
}
```

#### 3. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 처리: `src/main/java/fruition/access/user/service/AccountDeletionService.java`, 파기 호출 `src/main/java/fruition/access/cleanup/DataPurgeRequestJob.java`
- 하위 호출: document-svc `POST /internal/purge/users`, `POST /internal/purge/workspaces`, 이어서 ai-svc `POST /internal/ai/purge/users`, `POST /internal/ai/purge/workspaces`(`AiInternalClient`)

[↑ 요약으로 돌아가기](#summary-delete-api-auth-me)

</details>

<a id="summary-put-api-auth-me-avatar"></a>
### `PUT /api/auth/me/avatar`

| 항목 | 내용 |
|---|---|
| 목적 | 인증된 사용자의 프로필 사진을 올리거나 바꿉니다. PNG·JPEG·WebP·GIF, 1MB 이하. |
| 입력 | **Multipart** — `file` |
| 출력 | `200` 업로드 성공 — `MeResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다. |
| 주요 오류 | `400` 지원하지 않는 이미지 형식 — `ErrorResponse`<br>`401` 인증되지 않음 — `ErrorResponse`<br>`413` 이미지가 너무 큼 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-put-api-auth-me-avatar"></a>
### `PUT /api/auth/me/avatar` 상세

#### 1. Method + Path

`PUT /api/auth/me/avatar`

#### 2. 목적

프로필 사진을 올린다. 이미 있으면 덮어쓴다. 워크스페이스 아이콘 이미지(`PUT /api/workspaces/{workspace_id}/icon/image`)와
같은 규칙(형식·1MB·매직 바이트 판별·DB 저장)이다. 소셜 로그인 provider의 프로필 사진은 가져오지 않는다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| multipart | `file` | binary | 예 | PNG·JPEG·WebP·GIF, 1MB 이하 |

- Content-Type: `multipart/form-data`

형식은 **매직 바이트로 판별한다.** 선언된 Content-Type은 클라이언트가 정하는 값이라 믿지 않는다.
픽셀 크기는 보지 않는다 — 서버가 이미지를 디코딩하지 않고 그대로 저장·스트리밍하므로 바이트 상한만으로 저장량이 묶인다.

#### 5. Response body

- HTTP `200`: 업로드 성공 — `MeResponse`

```json
{
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "email": "user@example.com",
  "display_name": "표시 이름",
  "avatar_url": "/api/users/user_3f1c8a6b52d7411e9c04ab5d2e7f6081/avatar?v=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "oauth_providers": [],
  "consent_required": false
}
```

`avatar_url`의 `?v=`는 이미지 SHA-256이다. 사진을 바꾸면 값이 바뀌어 `GET /api/auth/me`·멤버 목록을 다시 읽는 화면은
새 URL로 새 이미지를 받고, 예전 URL로 캐시된 이미지는 쓰이지 않는다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | PNG·JPEG·WebP·GIF가 아니거나 파일이 비어 있음 | `UNSUPPORTED_USER_AVATAR` |
| `400` | multipart 요청이 아니거나 `file` part가 없음 | `INVALID_REQUEST` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `413` | 1MB를 넘음 | `USER_AVATAR_TOO_LARGE` |

2MB를 넘는 요청은 Spring multipart 한도(`spring.servlet.multipart.max-file-size`)에서 먼저 잘리지만, 같은 `413 USER_AVATAR_TOO_LARGE`로 돌려준다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다. 경로에 사용자 ID를 받지 않으므로 남의 사진은 바꿀 수 없다.
- 재업로드는 `user_avatars`의 기존 row를 덮어쓴다. 고아 이미지가 남지 않는다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$ACCESS/api/auth/me/avatar" \
  -H 'Authorization: Bearer <access_token>' \
  -F 'file=@me.png'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: updateAvatar`)
- 호출자: **호출자 미확인** (frontend 프로필 설정 화면 구현 예정, FruitionKR/Fruition-access#25)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-put-api-auth-me-avatar)

</details>

<a id="summary-delete-api-auth-me-avatar"></a>
### `DELETE /api/auth/me/avatar`

| 항목 | 내용 |
|---|---|
| 목적 | 인증된 사용자의 프로필 사진을 지웁니다. 사진이 없어도 200입니다. |
| 입력 | 없음 |
| 출력 | `200` 삭제 성공 — `MeResponse` |
| 조건 | 인증 필요<br>`Authorization: Bearer <access_token>`을 검증한다. |
| 주요 오류 | `401` 인증되지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-delete-api-auth-me-avatar"></a>
### `DELETE /api/auth/me/avatar` 상세

#### 1. Method + Path

`DELETE /api/auth/me/avatar`

#### 2. 목적

프로필 사진을 지운다. 응답의 `avatar_url`이 `null`이 되고, 프론트는 다시 이니셜 상자를 보여 준다.
사진이 없는 상태에서 불러도 실패하지 않고 현재 `MeResponse`를 돌려준다(멱등).

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

- 없음

#### 5. Response body

- HTTP `200`: 삭제 성공 — `MeResponse` (`avatar_url: null`)

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다.
- `user_avatars` row와 `users.avatar_content_type`·`avatar_hash`를 함께 지운다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$ACCESS/api/auth/me/avatar" \
  -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: deleteAvatar`)
- 호출자: **호출자 미확인** (frontend 프로필 설정 화면 구현 예정, FruitionKR/Fruition-access#25)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-delete-api-auth-me-avatar)

</details>

<a id="summary-post-api-auth-me-consents"></a>
### `POST /api/auth/me/consents`

| 항목 | 내용 |
|---|---|
| 목적 | 이용약관 버전이 바뀌어 로그인 응답·`GET /api/auth/me`의 `consent_required`가 `true`일 때 다시 동의를 받습니다. |
| 입력 | **Header** — `Authorization: Bearer <access_token>`<br>**Body** — `ConsentRequest` `{ "age_confirmed", "terms_version", "marketing_opt_in" }` |
| 출력 | `204` 동의 기록 완료 |
| 조건 | 가장 최근 동의의 이용약관 버전이 서버 현재 버전과 다르면(동의 기록이 없는 기존 회원 포함) `consent_required`가 `true`다. [약관 동의 플래그](#약관-동의-플래그-applegalconsent-required)가 `false`면 `consent_required`는 항상 `false`이고, 이 API는 아무것도 기록하지 않고 `204`를 준다. |
| 주요 오류 | `400` `CONSENT_REQUIRED` 만 18세 이상 확인·현재 이용약관 동의 없음<br>`401` 인증되지 않음 |

<a id="summary-put-api-auth-me-email"></a>
### `PUT /api/auth/me/email`

| 항목 | 내용 |
|---|---|
| 목적 | 본인을 다시 확인하고 새 이메일로 받은 인증번호 토큰으로 계정 이메일을 바꿉니다. 성공하면 기존 이메일로 변경 알림 메일이 갑니다. |
| 입력 | **Body** — `EmailChangeRequest`<br>**Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `200` 변경 성공 — `MeResponse` |
| 조건 | 인증 필요<br>비밀번호 계정은 `current_password`, 소셜 전용 계정은 10분 안의 직접 로그인, MFA를 켰으면 `mfa_code`<br>`purpose=email_change`로 **새 주소에** 발급받은 토큰이어야 한다. |
| 주요 오류 | `400` 유효하지 않은 `verification_token` — `ErrorResponse`<br>`401` 인증되지 않았거나 비밀번호·MFA 코드가 다름, 또는 소셜 계정이 최근에 로그인하지 않음 — `ErrorResponse`<br>`409` 이미 다른 계정(소셜 가입 포함)이 쓰는 이메일(`DUPLICATE_EMAIL`) — `ErrorResponse`<br>`429` 비밀번호·MFA 확인 시도 횟수 제한 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`PUT /api/auth/me/email`

#### 2. 목적

계정 이메일을 바꾼다. 인증번호는 **바꾸려는 새 주소로** 발송해, 그 메일함을 통제하는지 확인한다.
이메일을 바꾸면 비밀번호 재설정 메일도 새 주소로 가므로 계정을 넘겨줄 수 있는 변경이다. 그래서
세션(access token·refresh 쿠키)만 탈취한 사람이 바꾸지 못하도록 [회원 탈퇴](#summary-delete-api-auth-me)와
같은 방식으로 **본인을 다시 확인**하고, 성공하면 **기존 이메일로 변경 알림**을 보낸다.

흐름은 세 단계다.

1. `POST /api/auth/email-verifications` — `{"email": "<새 주소>", "purpose": "email_change"}`
2. `POST /api/auth/email-verifications/{verification_id}/confirm` — 코드 검증, `verification_token` 발급
3. `PUT /api/auth/me/email` — 토큰과 본인 확인 값(`current_password` / `mfa_code`)으로 확정

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다. 소셜 전용 계정은 이 토큰의 직접 로그인 시각(`auth_time`)이
  10분 안이어야 한다. refresh로 다시 받은 access token에는 `auth_time`이 없다.
- refresh 쿠키(`fruition_refresh_token`)를 함께 읽어 남길 세션을 식별한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `new_email` | `string` | 예 | 바꿀 새 이메일(255자 이하). 서버가 trim·소문자화한다 |
| body | `verification_token` | `string` | 예 | `purpose=email_change`로 받은 1회용 토큰 |
| body | `current_password` | `string` | 비밀번호 계정은 예 | 현재 비밀번호(128자 이하). 소셜 전용 계정은 보내지 않는다(보내도 무시) |
| body | `mfa_code` | `string` | MFA를 켰으면 예 | 인증 앱 코드 또는 복구 코드(64자 이하) |
| cookie | `fruition_refresh_token` | `string` | 아니오 | 현재 세션의 refresh token. 없으면 모든 세션이 폐기된다 |

```json
{
  "new_email": "new@example.com",
  "verification_token": "EXAMPLE-verification-token-not-real-0000000",
  "current_password": "password1234",
  "mfa_code": "482917"
}
```

#### 5. Response body

- HTTP `200`: 변경 성공 — `MeResponse`

```json
{
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "email": "new@example.com",
  "display_name": "표시 이름",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "oauth_providers": [
    "google"
  ]
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `new_email` 형식 오류 등 | `INVALID_REQUEST` |
| `400` | 토큰이 없거나 만료·소비됐거나 `new_email`과 다른 주소로 발급됨 | `INVALID_VERIFICATION_TOKEN` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `401` | 비밀번호 계정인데 `current_password`가 없거나 다름 | `INVALID_CREDENTIALS` |
| `401` | 소셜 전용 계정인데 10분 안에 직접 로그인한 access token이 아님 | `REAUTHENTICATION_REQUIRED` |
| `401` | MFA를 켰는데 `mfa_code`가 없거나 틀림 | `INVALID_MFA_CODE` |
| `409` | 이미 다른 계정이 그 이메일을 씀. provider와 상관없다(이메일 가입·소셜 가입 모두). 현재 본인 주소는 해당하지 않는다 | `DUPLICATE_EMAIL` |
| `429` | 현재 비밀번호 확인 실패가 한도를 넘음(비밀번호 변경·회원 탈퇴와 공유, 기본 5분에 5회) | `PASSWORD_CHANGE_RATE_LIMITED` |
| `429` | MFA 코드 확인 실패가 한도를 넘음 | `MFA_RATE_LIMITED` |

본인 확인 오류(`401`, `429`)는 토큰을 소비하지 않는다. 같은 `verification_token`으로 값을 고쳐 다시 보낼 수 있다.
본인 확인은 토큰 검증보다 먼저 하므로, 본인 확인과 토큰이 모두 틀리면 본인 확인 오류가 돌아온다.
중복 이메일(`409`)도 본인 확인 뒤, 토큰을 소비하기 전에 거절한다. 순서는 본인 확인 → 중복 확인 → 토큰 소비다.
소셜 계정이 쓰는 주소라도 `EMAIL_REGISTERED_WITH_OAUTH`가 아니라 `DUPLICATE_EMAIL`이다. 이미 로그인한 계정의 주소를 바꾸는 것이라
"그 소셜 계정으로 로그인해 연동" 안내가 맞지 않고, 다른 주소를 고르게 하면 되기 때문이다.

사전 중복 조회 이후에 들어온 같은 provider의 동시 요청도 커밋 전 `uq_users_email_provider` 제약 위반을 잡아
같은 `409 DUPLICATE_EMAIL` 계약으로 반환한다. 다른 provider끼리의 동시 변경은 DB 제약이 없어 사전 조회로만 막는다.

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- access token의 사용자 본인 계정만 변경한다.
- 계정은 `(email, provider)` 단위이므로 같은 provider에 그 이메일 계정이 이미 있으면 거부한다.
- 변경에 성공하면 현재 세션만 남기고 나머지 refresh token을 폐기한다. refresh 쿠키가 없으면
  남길 세션을 특정할 수 없어 전부 폐기한다.
- 본인 확인은 [회원 탈퇴](#summary-delete-api-auth-me)와 같다. 비밀번호 계정은 `current_password`, 비밀번호가 없는
  소셜 전용 계정은 10분 안의 직접 로그인, MFA를 켠 계정은 추가로 `mfa_code`.
- 변경이 커밋되면 **기존 이메일로** "계정 이메일이 변경되었습니다" 알림을 보낸다. 새 이메일은 로컬 파트 앞 두 글자만
  남기고 가리며(`ne*****@example.com`), 변경 시각(KST)과 본인이 아닐 때의 대응 안내를 담는다. 알림은 변경과 같은
  트랜잭션에서 outbox(`email_change_notifications`)에 남기고, 응답은 메일 발송을 기다리지 않는다. 발송 작업이
  `app.auth.email-change-notification.poll-delay-ms`(기본 10초)마다 보내고, 실패하면 1·2·4·8분 간격으로 최대 5번까지
  다시 시도한 뒤 실패 상태로 남기고 error 로그를 남긴다. 발송이 실패해도 변경은 되돌리지 않는다. 같은 이메일로 다시
  저장한 경우에는 보내지 않는다.
  SMTP가 설정되지 않은 환경(`spring.mail.host` 없음)에서는 메일 대신 로그로 남긴다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$ACCESS/api/auth/me/email" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -b 'fruition_refresh_token=<refresh_token>' \
  --data '{"new_email":"new@example.com","verification_token":"<verification_token>","current_password":"<current_password>"}'
```

```json
{
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081",
  "email": "new@example.com",
  "display_name": "표시 이름",
  "created_at": "2026-08-13T04:25:24.371948Z",
  "oauth_providers": [
    "google"
  ]
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: changeEmail`)
- 호출자: Fruition-frontend `src/entities/user/api/account.ts:29`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-put-api-auth-me-email)

</details>

<a id="summary-get-api-auth-me-sessions"></a>
### `GET /api/auth/me/sessions`

| 항목 | 내용 |
|---|---|
| 목적 | 폐기되지 않은 로그인 세션을 최근 로그인 순으로 반환합니다. |
| 입력 | **Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `200` 조회 성공 — `SessionListResponse` |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 인증되지 않음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`GET /api/auth/me/sessions`

#### 2. 목적

로그인된 기기 목록이다. refresh token 하나가 세션 하나에 대응한다.

#### 3. Auth 필요 여부

- 필요
- refresh 쿠키(`fruition_refresh_token`)를 함께 읽어 현재 세션을 식별한다.

#### 4. Request body

- 요청 본문 없음

#### 5. Response body

- HTTP `200`: 조회 성공 — `SessionListResponse`
- refresh 쿠키를 함께 읽어 지금 요청을 보낸 세션에 `current: true`를 단다. 쿠키가 없으면 전부 `false`다.

```json
{
  "sessions": [
    {
      "session_id": 42,
      "user_agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
      "current": true,
      "created_at": "2026-09-07T04:25:24.371948Z",
      "expires_at": "2026-09-21T04:25:24.371948Z"
    }
  ]
}
```

`user_agent`는 로그인 시점의 원문이다. 이 컬럼이 생기기 전에 발급된 세션은 `null`이라
화면에서 "알 수 없는 기기"로 보여야 한다.

`created_at`이 사실상 마지막 사용 시각이다. refresh는 토큰을 회전시켜 새 세션 행을 만들고
옛 행을 폐기하므로, 활성 세션의 `created_at`은 마지막 갱신 시점이다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 정렬: `created_at` 내림차순 고정
- 폐기된 세션은 반환하지 않는다

#### 8. 권한 규칙

- 토큰의 사용자 본인 세션만 반환한다.

#### 9. 예시 요청/응답

```bash
curl "$ACCESS/api/auth/me/sessions" \
  -H 'Authorization: Bearer <access_token>' \
  -b 'fruition_refresh_token=<refresh_token>'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: sessions`)
- 호출자: Fruition-frontend `src/entities/user/api/sessions.ts:14`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-get-api-auth-me-sessions)

</details>

<a id="summary-delete-api-auth-me-sessions-session-id"></a>
### `DELETE /api/auth/me/sessions/{session_id}`

| 항목 | 내용 |
|---|---|
| 목적 | 지정한 세션의 refresh token을 폐기합니다. |
| 입력 | **Path** — `session_id`: `integer` |
| 출력 | `204` 폐기 성공 — 본문 없음 |
| 조건 | 인증 필요<br>본인 세션이어야 한다. |
| 주요 오류 | `401` 인증되지 않음 — `ErrorResponse`<br>`404` 세션을 찾을 수 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`DELETE /api/auth/me/sessions/{session_id}`

#### 2. 목적

특정 기기를 로그아웃시킨다. 현재 세션을 지정하면 스스로 로그아웃하는 것이라 허용한다 —
다만 refresh 쿠키는 지워지지 않으므로, 현재 세션을 끊을 때는
[`POST /api/auth/logout`](#summary-post-api-auth-logout)을 쓰는 편이 낫다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `session_id` | `integer` | 예 | `GET /api/auth/me/sessions`가 준 세션 ID |

- 요청 본문 없음

#### 5. Response body

- HTTP `204`: 폐기 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |
| `404` | 세션이 없거나, 남의 세션이거나, 이미 폐기됨 | `SESSION_NOT_FOUND` |

셋을 모두 `404`로 통일한다. 남의 세션을 `403`으로 구분하면 세션 ID 존재 여부가 드러난다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인 세션만 폐기할 수 있다.
- 폐기된 세션의 refresh token으로는 더 이상 access token을 갱신할 수 없다. 이미 발급된
  access token은 만료(기본 900초)까지 유효하다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$ACCESS/api/auth/me/sessions/42" \
  -H 'Authorization: Bearer <access_token>' \
  -i
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: revokeSession`)
- 호출자: Fruition-frontend `src/entities/user/api/sessions.ts:20`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-delete-api-auth-me-sessions-session-id)

</details>

<a id="summary-put-api-auth-me-password"></a>
### `PUT /api/auth/me/password`

| 항목 | 내용 |
|---|---|
| 목적 | 현재 비밀번호를 확인하고 새 비밀번호로 바꿉니다. 성공하면 현재 세션을 제외한 refresh token이 폐기됩니다. |
| 입력 | **Body** — `PasswordChangeRequest`<br>**Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `204` 변경 성공 — 본문 없음 |
| 조건 | 인증 필요<br>비밀번호를 쓰는 계정(`provider=local`)이어야 한다.<br>MFA를 켰으면 `mfa_code`도 맞아야 한다. |
| 주요 오류 | `400` 비밀번호를 쓰지 않는 계정 — `ErrorResponse`<br>`401` 인증되지 않았거나 현재 비밀번호가 다름(`INVALID_CREDENTIALS`), MFA 코드 없음·틀림(`INVALID_MFA_CODE`) — `ErrorResponse`<br>`429` 현재 비밀번호 시도 제한 초과(`PASSWORD_CHANGE_RATE_LIMITED`), MFA 시도 제한 초과(`MFA_RATE_LIMITED`) |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`PUT /api/auth/me/password`

#### 2. 목적

로그인 상태에서 비밀번호를 바꾼다. 비로그인 흐름인
[`POST /api/auth/password-reset`](#summary-post-api-auth-password-reset)과 달리 인증번호가 아니라
**현재 비밀번호**로 본인을 확인한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.
- refresh 쿠키(`fruition_refresh_token`)를 함께 읽어 현재 세션을 식별한다. 쿠키 path가
  `/api/auth`라 이 경로에는 자동으로 실린다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `current_password` | `string` | 예 | 현재 비밀번호 |
| body | `new_password` | `string` | 예 | 새 비밀번호(8~72자) |
| body | `mfa_code` | `string` | MFA를 켰으면 예 | 인증 앱 코드 또는 복구 코드(64자 이하). MFA를 끈 계정은 무시한다 |
| cookie | `fruition_refresh_token` | `string` | 아니오 | 현재 세션의 refresh token. 없으면 모든 세션이 폐기된다 |

```json
{
  "current_password": "password1234",
  "new_password": "newPassword1234",
  "mfa_code": "482917"
}
```

#### 5. Response body

- HTTP `204`: 변경 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `new_password` 길이 위반 등 | `INVALID_REQUEST` |
| `400` | OAuth로만 가입해 비밀번호가 없는 계정 | `PASSWORD_LOGIN_UNAVAILABLE` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `401` | 현재 비밀번호가 다름 | `INVALID_CREDENTIALS` |
| `401` | MFA를 켰는데 `mfa_code`가 없거나 틀림 | `INVALID_MFA_CODE` |
| `429` | 현재 비밀번호 실패가 한도를 넘음((사용자, 출처)별 5분에 5회) | `PASSWORD_CHANGE_RATE_LIMITED` |
| `429` | MFA 코드 확인 시도가 한도를 넘음(로그인 MFA·이메일 변경·회원 탈퇴·인증번호 비밀번호 변경과 공유, 사용자별 5분에 5회) | `MFA_RATE_LIMITED` |

검사 순서는 비밀번호 변경 시도 제한 → 비밀번호 계정 여부 → 현재 비밀번호 → MFA 코드다. 현재 비밀번호가 틀리면
MFA 코드는 확인하지 않고 `INVALID_CREDENTIALS`가 돌아오므로, 비밀번호를 모르는 요청은 MFA 시도 횟수를 쓰지 않는다.
`PASSWORD_CHANGE_RATE_LIMITED` 예산은 현재 비밀번호 실패만 센다. MFA 코드 실패는 이 예산을 쓰지 않고
`MFA_RATE_LIMITED`의 사용자별 제한에만 잡힌다. MFA 오류로 거절되면 비밀번호는 바뀌지 않는다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다.
- `password_hash`가 없는 계정(OAuth 전용)은 "현재 비밀번호"가 성립하지 않아 `400`이다.
- MFA를 켠 계정은 현재 비밀번호에 더해 `mfa_code`(인증 앱 코드 또는 복구 코드)를 요구한다. access token과
  현재 비밀번호를 함께 가진 사람이 2차 인증 없이 비밀번호를 바꾸지 못하게 하기 위해서다. MFA 사용 여부는
  [`GET /api/auth/me/mfa`](#summary-get-api-auth-me-mfa)의 `enabled`로 알 수 있다.
- 성공하면 **현재 세션을 제외한** refresh token을 전부 폐기한다. 비밀번호가 샜을 때 다른 기기의
  세션을 끊으면서, 방금 현재 비밀번호로 본인 확인을 마친 사용자는 로그아웃시키지 않기 위해서다.
  refresh 쿠키가 없으면 지킬 세션을 특정할 수 없어 전부 폐기한다.
- 비로그인 `password-reset`이 세션을 **전부** 폐기하는 것과 다르다. 그쪽은 요청자가 메일함만
  통제하고 있어 지켜줄 현재 세션이 없다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$ACCESS/api/auth/me/password" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -b 'fruition_refresh_token=<refresh_token>' \
  --data '{"current_password":"password1234","new_password":"newPassword1234"}' \
  -i
```

```
HTTP/1.1 204 No Content
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: changePassword`)
- 호출자: Fruition-frontend `src/entities/user/api/account.ts:20`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-put-api-auth-me-password)

</details>

<a id="summary-post-api-auth-me-password-verification"></a>
### `POST /api/auth/me/password/verification`

| 항목 | 내용 |
|---|---|
| 목적 | 로그인한 사용자의 계정 이메일로 비밀번호 변경용 인증번호(`purpose=password_change`)를 보냅니다. |
| 입력 | 본문 없음 |
| 출력 | `202` 인증번호 발급 — `EmailVerificationResponse` |
| 조건 | 인증 필요<br>비밀번호를 쓰는 계정(`provider=local`)이어야 한다. |
| 주요 오류 | `400` 비밀번호를 쓰지 않는 계정 — `ErrorResponse`<br>`401` 인증되지 않음<br>`429` 재요청 제한 초과 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/me/password/verification`

#### 2. 목적

현재 비밀번호를 모르는 로그인 사용자가 비밀번호를 바꾸는 흐름의 1단계다. 받는 주소를 요청에서 받지 않고
**access token 사용자의 계정 이메일**로만 보낸다. 로그인 세션만으로 다른 메일함에 코드를 보내거나 다른 계정의
비밀번호를 바꿀 수 없게 하기 위해서다.

전체 흐름:

1. `POST /api/auth/me/password/verification` — 계정 이메일로 코드 발송, `verification_id` 발급
2. [`POST /api/auth/email-verifications/{verification_id}/confirm`](#summary-post-api-auth-email-verifications-verification-id-confirm) — 코드 검증, `verification_token` 발급
3. [`PUT /api/auth/me/password/verified`](#summary-put-api-auth-me-password-verified) — 토큰과 새 비밀번호로 변경

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

없음.

#### 5. Response body

- HTTP `202`: `EmailVerificationResponse` — [`POST /api/auth/email-verifications`](#summary-post-api-auth-email-verifications)와 같다.

```json
{
  "verification_id": "ev_0123456789abcdef0123456789abcdef",
  "expires_in": 300,
  "retry_after": 60
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | OAuth로만 가입해 비밀번호가 없는 계정 | `PASSWORD_LOGIN_UNAVAILABLE` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `429` | 같은 계정 이메일의 `password_change` 재요청 쿨다운·일일 한도 초과 | `VERIFICATION_RATE_LIMITED` |
| `502` | 메일 발송 실패 | `EMAIL_SEND_FAILED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다. 받는 주소는 계정에 저장된 이메일이다.
- `password_change` 코드는 이 경로로만 발급된다. 공개 `POST /api/auth/email-verifications`의 `purpose`로는
  받을 수 없다(`400 INVALID_REQUEST`).
- 재요청 제한은 `(계정 이메일, password_change)` 단위로 다른 목적과 같은 규칙(쿨다운·일일 한도)을 따른다.
  새 코드를 받으면 같은 이메일·목적의 이전 미사용 코드는 만료된다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/me/password/verification" \
  -H 'Authorization: Bearer <access_token>' \
  -i
```

```
HTTP/1.1 202 Accepted
Content-Type: application/json

{"verification_id":"ev_0123456789abcdef0123456789abcdef","expires_in":300,"retry_after":60}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: requestPasswordChangeVerification`)
- 호출자: **호출자 미확인** (frontend 로그인 상태 비밀번호 변경 전환 예정)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-me-password-verification)

</details>

<a id="summary-put-api-auth-me-password-verified"></a>
### `PUT /api/auth/me/password/verified`

| 항목 | 내용 |
|---|---|
| 목적 | 계정 이메일로 받은 인증번호 토큰으로 본인을 확인하고 비밀번호를 바꿉니다. 성공하면 현재 세션을 제외한 refresh token이 폐기됩니다. |
| 입력 | **Body** — `VerifiedPasswordChangeRequest`<br>**Cookie** — `fruition_refresh_token`(선택) |
| 출력 | `204` 변경 성공 — 본문 없음 |
| 조건 | 인증 필요<br>비밀번호를 쓰는 계정(`provider=local`)이어야 한다.<br>MFA를 켰으면 `mfa_code`도 맞아야 한다. |
| 주요 오류 | `400` 유효하지 않은 verification_token, 비밀번호를 쓰지 않는 계정 — `ErrorResponse`<br>`401` 인증되지 않음, MFA 코드 없음·틀림(`INVALID_MFA_CODE`)<br>`429` MFA 시도 제한 초과(`MFA_RATE_LIMITED`) |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`PUT /api/auth/me/password/verified`

#### 2. 목적

[`POST /api/auth/me/password/verification`](#summary-post-api-auth-me-password-verification)으로 시작한 흐름의
마지막 단계다. [`PUT /api/auth/me/password`](#summary-put-api-auth-me-password)는 현재 비밀번호로, 이 API는
계정 이메일 인증번호로 본인을 확인한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.
- refresh 쿠키(`fruition_refresh_token`)를 함께 읽어 현재 세션을 식별한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `new_password` | `string` | 예 | 새 비밀번호(8~72자) |
| body | `verification_token` | `string` | 예 | `POST /api/auth/me/password/verification`의 코드를 confirm해 받은 1회용 토큰 |
| body | `mfa_code` | `string` | MFA를 켰으면 예 | 인증 앱 코드 또는 복구 코드(64자 이하). MFA를 끈 계정은 무시한다 |
| cookie | `fruition_refresh_token` | `string` | 아니오 | 현재 세션의 refresh token. 없으면 모든 세션이 폐기된다 |

```json
{
  "new_password": "newPassword1234",
  "verification_token": "EXAMPLE-verification-token-not-real-0000000",
  "mfa_code": "482917"
}
```

#### 5. Response body

- HTTP `204`: 변경 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `new_password` 길이 위반 등 | `INVALID_REQUEST` |
| `400` | 토큰이 없거나 만료·사용됨, 또는 이 계정 이메일·`password_change` 목적으로 발급된 토큰이 아님 | `INVALID_VERIFICATION_TOKEN` |
| `400` | OAuth로만 가입해 비밀번호가 없는 계정 | `PASSWORD_LOGIN_UNAVAILABLE` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `401` | MFA를 켰는데 `mfa_code`가 없거나 틀림 | `INVALID_MFA_CODE` |
| `429` | MFA 코드 확인 시도가 한도를 넘음(로그인 MFA·이메일 변경·회원 탈퇴·현재 비밀번호 변경과 공유, 사용자별 5분에 5회) | `MFA_RATE_LIMITED` |

검사 순서는 비밀번호 계정 여부 → 토큰 확인 → MFA 코드다. 유효한 토큰이 없는 요청은 MFA 코드를 확인하지 않아
로그인 등과 공유하는 MFA 시도 한도를 쓰지 않는다. MFA 오류(`401`, `429`)가 나면 토큰 소비가 함께 되돌려지므로
같은 `verification_token`으로 `mfa_code`를 고쳐 다시 보낼 수 있다. MFA 코드와 토큰이 모두 틀리면 토큰 오류
(`400 INVALID_VERIFICATION_TOKEN`)가 돌아온다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인만 대상이다.
- MFA를 켠 계정은 이메일 인증번호에 더해 `mfa_code`(인증 앱 코드 또는 복구 코드)를 요구한다. 세션과 메일함을 함께
  가진 사람이 2차 인증 없이 비밀번호를 바꾸지 못하게 하기 위해서다. MFA 사용 여부는
  [`GET /api/auth/me/mfa`](#summary-get-api-auth-me-mfa)의 `enabled`로 알 수 있다.
- `verification_token`은 발급 당시 **계정 이메일**과 `password_change` 목적에 묶인다. 다른 계정 이메일로 받은
  토큰, `password_reset`·`signup`·`email_change` 토큰은 모두 `INVALID_VERIFICATION_TOKEN`이다. 반대로 이 토큰은
  비로그인 `POST /api/auth/password-reset`에 쓸 수 없다.
- 코드 요청 뒤 이메일을 바꿨다면 옛 이메일로 받은 토큰은 거절된다. 새 이메일로 다시 요청한다.
- 토큰은 1회용이며 `verification_token`의 만료(`expires_in`) 안에 써야 한다. 거절된 요청은 토큰을 소비하지 않는다.
- 성공하면 [`PUT /api/auth/me/password`](#summary-put-api-auth-me-password)처럼 **현재 세션을 제외한**
  refresh token을 폐기한다.

#### 9. 예시 요청/응답

```bash
curl -X PUT "$ACCESS/api/auth/me/password/verified" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  -b 'fruition_refresh_token=<refresh_token>' \
  --data '{"new_password":"newPassword1234","verification_token":"<verification_token>"}' \
  -i
```

```
HTTP/1.1 204 No Content
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: changePasswordWithVerification`)
- 호출자: **호출자 미확인** (frontend 로그인 상태 비밀번호 변경 전환 예정)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-put-api-auth-me-password-verified)

</details>

<a id="summary-post-api-auth-login-mfa"></a>
### `POST /api/auth/login/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | 다단계 인증 코드로 로그인을 마칩니다. |
| 입력 | **Body** — `MfaLoginRequest` |
| 출력 | `200` 로그인 성공 — `LoginResponse` |
| 조건 | 인증 불필요. `mfa_token`이 신원을 대신한다. |
| 주요 오류 | `400` mfa_token 만료·소비됨<br>`401` 코드가 올바르지 않음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/login/mfa`

#### 2. 목적

`POST /api/auth/login` 또는 `POST /api/auth/oauth/exchange`가 `mfa_required: true`를 돌려줬을 때 두 번째 단계를 마친다.

#### 3. Auth 필요 여부

- 불필요. 아직 로그인 전이며 `mfa_token`은 비밀번호 또는 OAuth 인증을 통과했다는 증거다.
- `mfa_token`은 1회용이고 기본 300초 뒤 만료된다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `mfa_token` | `string` | 예 | 로그인 1단계 응답의 토큰 |
| body | `code` | `string` | 예 | 인증 앱의 6자리 코드 **또는** 복구 코드 |

```json
{
  "mfa_token": "EXAMPLE-mfa-token-not-real",
  "code": "482917"
}
```

복구 코드도 같은 자리에 넣는다. 별도 엔드포인트를 두지 않는다.

#### 5. Response body

`POST /api/auth/login`의 성공 응답과 같다. refresh 토큰은 HttpOnly 쿠키로 나간다.

```json
{
  "access_token": "<JWT>",
  "token_type": "Bearer",
  "expires_in": 900
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `mfa_token`이 없거나 만료·소비됨 | `INVALID_MFA_CHALLENGE` |
| `401` | 코드가 올바르지 않음 | `INVALID_MFA_CODE` |

일반·OAuth 로그인 모두 MFA가 켜져 있으면 challenge만 반환하며 access/refresh 토큰과 refresh 쿠키는 발급하지 않는다.

MFA 활성화·로그인·해제의 코드 검증은 사용자별로 300초 동안 총 5회까지 허용한다. 초과하면 `429 MFA_RATE_LIMITED`와 `Retry-After`를 반환한다. 실패한 요청의 롤백이나 challenge 재발급으로 횟수가 초기화되지 않는다.

코드와 challenge 소비는 DB 잠금으로 직렬화한다. 같은 TOTP·복구 코드·challenge를 동시에 제출해도 하나만 성공한다.

**코드가 틀려도 `mfa_token`은 살아 있다.** 오타 한 번에 비밀번호부터 다시 넣게 만들지 않는다.
TOTP인지 복구 코드인지는 구분해 알려주지 않는다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 같은 30초 창의 TOTP 코드는 두 번 쓸 수 없다. 가로챈 코드의 재사용을 막는다.
- 시계 오차를 감안해 앞뒤 한 창(±30초)까지 받아준다.
- 복구 코드는 한 번 쓰면 소멸한다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/login/mfa" \
  -H 'Content-Type: application/json' \
  --data '{"mfa_token":"<token>","code":"482917"}'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: loginMfa`)
- 호출자: Fruition-frontend `src/entities/user/api/mfa.ts:5`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-login-mfa)

</details>

<a id="summary-get-api-auth-me-mfa"></a>
### `GET /api/auth/me/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | 다단계 인증 상태를 반환합니다. |
| 입력 | 없음 |
| 출력 | `200` 조회 성공 — `MfaStatusResponse` |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 인증되지 않음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`GET /api/auth/me/mfa`

#### 2. 목적

설정 화면이 "2단계 인증: 켜짐/꺼짐"과 남은 복구 코드 수를 보여주는 데 쓴다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

- 요청 본문 없음

#### 5. Response body

```json
{
  "enabled": true,
  "activated_at": "2026-09-07T21:40:11.204813Z",
  "remaining_recovery_codes": 9
}
```

`enabled`는 **활성화까지 끝난 경우에만** true다. 등록만 하고 코드 검증을 통과하지 않았으면 false다 —
그 상태는 로그인을 막지 않으므로 화면에도 "켜짐"으로 보이면 안 된다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 본인 상태만 조회한다.

#### 9. 예시 요청/응답

```bash
curl "$ACCESS/api/auth/me/mfa" -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: mfaStatus`)
- 호출자: Fruition-frontend `src/entities/user/api/mfa.ts:18`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-get-api-auth-me-mfa)

</details>

<a id="summary-post-api-auth-me-mfa"></a>
### `POST /api/auth/me/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | secret과 복구 코드를 발급합니다(등록 1단계). |
| 입력 | 없음 |
| 출력 | `200` 발급 성공 — `MfaRegistrationResponse` |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 인증되지 않음<br>`409` 이미 켜져 있음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/me/mfa`

#### 2. 목적

QR로 보여줄 secret과 복구 코드를 만든다. **이 단계로는 켜지지 않는다** —
로그인은 그대로 비밀번호만으로 통과한다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

- 요청 본문 없음

#### 5. Response body

```json
{
  "secret": "JBSWY3DPEHPK3PXP",
  "otpauth_uri": "otpauth://totp/Fruition%3Auser%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Fruition",
  "recovery_codes": ["A3F2K9QZ", "..."]
}
```

`recovery_codes`는 **이 응답에서만** 볼 수 있다. 서버에는 SHA-256 해시만 남아 다시 조회할 수 없다.
`otpauth_uri`를 QR로 만들면 인증 앱이 바로 읽는다. `secret`은 QR을 못 쓸 때 수동 입력용이다.

이미 등록만 해둔 상태에서 다시 호출하면 새 secret으로 갈아끼우고 복구 코드도 새로 발급한다 —
옛 복구 코드는 옛 secret과 짝이라 함께 버린다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |
| `409` | 이미 활성화됨. 해제 후 다시 등록해야 한다 | `MFA_ALREADY_ENABLED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 본인 계정에만 등록한다.
- secret은 AES-GCM으로 암호화해 저장한다. 검증에 원문이 필요해 해시로 둘 수 없기 때문이며,
  평문으로 두면 DB 유출 시 MFA가 통째로 무력화된다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/me/mfa" -H 'Authorization: Bearer <access_token>'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: registerMfa`)
- 호출자: Fruition-frontend `src/entities/user/api/mfa.ts:23`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-me-mfa)

</details>

<a id="summary-post-api-auth-me-mfa-activate"></a>
### `POST /api/auth/me/mfa/activate`

| 항목 | 내용 |
|---|---|
| 목적 | 코드를 확인하고 다단계 인증을 켭니다(등록 2단계). |
| 입력 | **Body** — `MfaCodeRequest` |
| 출력 | `204` 활성화 성공 — 본문 없음 |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 코드가 올바르지 않음<br>`404` 등록된 설정 없음<br>`409` 이미 켜져 있음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/me/mfa/activate`

#### 2. 목적

인증 앱이 만든 코드가 맞는지 확인하고 실제로 켠다. **이 단계를 통과해야 로그인에 코드가 요구된다.**

두 단계로 나눈 이유: 등록 즉시 켜버리면 QR을 잘못 스캔했거나 앱 시계가 틀어진 사용자가
다음 로그인에서 자기 계정에 못 들어간다.

#### 3. Auth 필요 여부

- 필요

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `code` | `string` | 예 | 인증 앱의 6자리 코드 |

```json
{
  "code": "482917"
}
```

#### 5. Response body

- HTTP `204`: 활성화 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | 코드가 올바르지 않음 | `INVALID_MFA_CODE` |
| `404` | `POST /api/auth/me/mfa`를 먼저 호출하지 않음 | `MFA_NOT_ENABLED` |
| `409` | 이미 활성화됨 | `MFA_ALREADY_ENABLED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 활성화에 쓴 시간 창은 소비 처리한다. 같은 코드로 바로 로그인할 수 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/me/mfa/activate" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"code":"482917"}'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: activateMfa`)
- 호출자: Fruition-frontend `src/entities/user/api/mfa.ts:28`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-me-mfa-activate)

</details>

<a id="summary-delete-api-auth-me-mfa"></a>
### `DELETE /api/auth/me/mfa`

| 항목 | 내용 |
|---|---|
| 목적 | 코드로 본인을 확인한 뒤 다단계 인증을 해제합니다. |
| 입력 | **Body** — `MfaCodeRequest` |
| 출력 | `204` 해제 성공 — 본문 없음 |
| 조건 | 인증 필요 |
| 주요 오류 | `401` 코드가 올바르지 않음<br>`404` 켜져 있지 않음 |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`DELETE /api/auth/me/mfa`

#### 2. 목적

다단계 인증을 끈다. secret과 남은 복구 코드가 모두 지워진다.

#### 3. Auth 필요 여부

- 필요
- access token만으로는 부족하다. 토큰을 탈취한 쪽이 MFA를 그냥 꺼버릴 수 있기 때문에
  코드를 한 번 더 요구한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `code` | `string` | 예 | 인증 앱의 6자리 코드 **또는** 복구 코드 |

```json
{
  "code": "482917"
}
```

복구 코드도 받는 이유: 기기를 잃은 사용자가 MFA를 끄고 다시 등록할 수 있어야 한다.
OAuth 전용 계정은 비밀번호가 없으므로 코드가 유일한 확인 수단이다.

#### 5. Response body

- HTTP `204`: 해제 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | 코드가 올바르지 않음 | `INVALID_MFA_CODE` |
| `404` | 켜져 있지 않음 | `MFA_NOT_ENABLED` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 본인 계정만 해제한다.
- 해제 후에는 `POST /api/auth/login`이 다시 토큰을 바로 돌려준다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$ACCESS/api/auth/me/mfa" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"code":"482917"}'
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: disableMfa`)
- 호출자: Fruition-frontend `src/entities/user/api/mfa.ts:35`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-delete-api-auth-me-mfa)

</details>

<a id="summary-post-api-auth-me-oauth-accounts-provider-link"></a>
### `POST /api/auth/me/oauth-accounts/{provider}/link`

| 항목 | 내용 |
|---|---|
| 목적 | 로그인한 사용자가 소셜 계정 연동을 시작할 1회용 연동 토큰을 발급합니다. |
| 입력 | **Path** — `provider`: `string` |
| 출력 | `200` 토큰 발급 — `OAuthLinkStartResponse` |
| 조건 | 인증 필요<br>토큰은 이 사용자와 `provider`에 묶이고 60초 뒤 만료된다. |
| 주요 오류 | `401` 인증되지 않음<br>`404` 지원하지 않는 provider — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/me/oauth-accounts/{provider}/link`

#### 2. 목적

설정 화면에서 소셜 계정(google, kakao, naver)을 현재 계정의 로그인 수단으로 연결하는 흐름의 첫 단계다.
연결이 끝나면 그 소셜 계정으로 로그인했을 때 새 계정이 생기지 않고 현재 계정으로 로그인된다.

흐름은 네 단계다.

1. `POST /api/auth/me/oauth-accounts/{provider}/link` — `link_token` 발급(60초, 1회용)
2. 프론트가 `/oauth2/authorization/{provider}?mode=link&link_token=<link_token>`으로 이동해 소셜 인증을 받는다.
   로그인과 같은 OAuth 경로이며, 인가 요청을 만들 때 토큰을 소비해 연동 대상 사용자를 OAuth `state`에 묶는다.
3. 인증을 마치면 OAuth 콜백 주소(`app.oauth.frontend-redirect-uri`)로 `?link_code=<code>`가 붙어 돌아온다(60초, 1회용).
   토큰이 없거나 무효이거나 다른 provider용이거나 소셜 인증이 실패하면 `?link=failed`로 돌아온다.
   연동 모드에서는 로그인용 `?code=`를 발급하지 않고 계정을 만들지도 않는다.
4. 프론트가 로그인한 채로 [`POST /api/auth/me/oauth-accounts/link/confirm`](#summary-post-api-auth-me-oauth-accounts-link-confirm)에 `link_code`를 제출하면 연결된다.

콜백에서 바로 연결하지 않고 4단계 확정을 두는 이유: 토큰이 URL에 실리므로, 공격자가 자기 토큰을 넣은 링크를
피해자에게 열게 하면 피해자의 소셜 계정이 공격자 계정에 붙을 수 있다(연동 CSRF). 확정 API는 토큰을 발급받은
사용자와 확정하는 로그인 사용자가 같아야만 연결하므로 이 공격이 막힌다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `provider` | `string` | 예 | 등록된 OAuth provider ID(`google`, `kakao`, `naver`) |

- 요청 본문 없음

#### 5. Response body

- HTTP `200`: 토큰 발급 — `OAuthLinkStartResponse`

```json
{
  "link_token": "EXAMPLE-link-token-not-real-0000000000000000"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |
| `404` | 등록되지 않은 provider | `UNSUPPORTED_OAUTH_PROVIDER` |

이미 연결된 provider인지는 여기서 검사하지 않는다. 확정 단계에서 `409`로 거절된다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인 계정에만 연결할 수 있다. 경로에 사용자 ID를 받지 않는다.
- 연동 토큰은 발급한 `provider`의 인가 요청에서만 소비된다. 다른 provider로 쓰면 `?link=failed`가 된다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/me/oauth-accounts/google/link" \
  -H 'Authorization: Bearer <access_token>'
```

```json
{
  "link_token": "EXAMPLE-link-token-not-real-0000000000000000"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 연동 OAuth 흐름: `src/main/java/fruition/access/security/oauth/OAuthLinkFlow.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: startOAuthLink`)
- 호출자: **호출자 미확인** (frontend 구현 예정, FruitionKR/Fruition-frontend#105)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-me-oauth-accounts-provider-link)

</details>

<a id="summary-post-api-auth-me-oauth-accounts-link-confirm"></a>
### `POST /api/auth/me/oauth-accounts/link/confirm`

| 항목 | 내용 |
|---|---|
| 목적 | 연동 콜백이 넘긴 `link_code`로 소셜 계정을 현재 계정에 연결합니다. |
| 입력 | **Body** — `OAuthLinkConfirmRequest` |
| 출력 | `204` 연동 성공 — 본문 없음 |
| 조건 | 인증 필요<br>연동을 시작한 사용자 본인이 제출해야 한다. |
| 주요 오류 | `400` 유효하지 않거나 만료됐거나 다른 사용자가 시작한 code — `ErrorResponse`<br>`401` 인증되지 않음<br>`409` 다른 계정에 연결된 소셜 계정이거나 같은 provider가 이미 연결됨 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`POST /api/auth/me/oauth-accounts/link/confirm`

#### 2. 목적

소셜 계정 연동 흐름의 마지막 단계다. 전체 흐름과 확정 단계를 두는 이유는
[`POST /api/auth/me/oauth-accounts/{provider}/link`](#summary-post-api-auth-me-oauth-accounts-provider-link) 참고.

연동은 `user_oauth_accounts`에 행 하나를 추가할 뿐이다. 새 계정을 만들지 않고, 같은 이메일의 다른 계정과
합치지도 않는다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| body | `link_code` | `string` | 예 | 연동 콜백이 `?link_code=`로 넘긴 1회용 code(60초) |

```json
{
  "link_code": "EXAMPLE-link-code-not-real-00000000000000000"
}
```

#### 5. Response body

- HTTP `204`: 연동 성공, 본문 없음. 그 소셜 계정이 이미 내 계정에 연결돼 있어도 `204`다.

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `400` | `link_code`가 비었음 | `INVALID_REQUEST` |
| `400` | `link_code`가 없거나 만료·소비됐거나 다른 사용자가 시작한 연동 | `INVALID_OAUTH_LINK_CODE` |
| `401` | access token이 없거나 유효하지 않음 | — |
| `409` | 그 소셜 계정이 다른 계정에 연결돼 있거나, 내 계정에 같은 provider의 다른 소셜 계정이 이미 연결됨 | `OAUTH_ACCOUNT_ALREADY_LINKED` |

`link_code`는 조회와 동시에 소비되므로, 거절된 code는 다시 쓸 수 없고 연동을 처음부터 다시 시작해야 한다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- `link_code`에 묶인 사용자(연동 토큰을 발급받은 사용자)와 access token의 사용자가 같아야 한다.
- 연동·해제는 사용자 행을 잠가 사용자 단위로 직렬화한다. 다른 사용자가 같은 소셜 계정을 동시에 연동하면
  한쪽만 성공하고 다른 쪽은 `409`다.
- 연동해도 다른 세션(refresh token)은 폐기하지 않는다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/me/oauth-accounts/link/confirm" \
  -H 'Authorization: Bearer <access_token>' \
  -H 'Content-Type: application/json' \
  --data '{"link_code":"<link_code>"}' \
  -i
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: confirmOAuthLink`)
- 호출자: **호출자 미확인** (frontend 구현 예정, FruitionKR/Fruition-frontend#105)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-me-oauth-accounts-link-confirm)

</details>

<a id="summary-delete-api-auth-me-oauth-accounts-provider"></a>
### `DELETE /api/auth/me/oauth-accounts/{provider}`

| 항목 | 내용 |
|---|---|
| 목적 | 연결된 소셜 계정의 연동을 해제합니다. |
| 입력 | **Path** — `provider`: `string` |
| 출력 | `204` 해제 성공 — 본문 없음 |
| 조건 | 인증 필요<br>가입할 때 쓴 provider는 해제할 수 없다. |
| 주요 오류 | `401` 인증되지 않음<br>`404` 연결되지 않은 provider — `ErrorResponse`<br>`409` 가입할 때 쓴 provider — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

#### 1. Method + Path

`DELETE /api/auth/me/oauth-accounts/{provider}`

#### 2. 목적

현재 계정에 연결된 소셜 로그인 수단을 끊는다. 해제 후 그 소셜 계정으로 로그인하면 현재 계정으로 들어오지 않는다.
같은 이메일의 계정(현재 계정)이 있으므로 신규 가입도 되지 않고 `?error=email_registered`로 돌아오며, 다시 쓰려면 설정에서 연동한다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다.

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `provider` | `string` | 예 | 해제할 provider ID(`google`, `kakao`, `naver`) |

- 요청 본문 없음

#### 5. Response body

- HTTP `204`: 해제 성공, 본문 없음

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |
| `404` | 내 계정에 연결되지 않은 provider | `OAUTH_ACCOUNT_NOT_FOUND` |
| `409` | 가입할 때 쓴 provider(`users.provider`)라 해제할 수 없음 | `OAUTH_UNLINK_NOT_ALLOWED` |

가입 provider를 막는 이유: 해제하면 그 provider로 다시 로그인할 때 같은 `(email, provider)` 계정을 새로
만들려다 막히고, OAuth 전용 계정은 마지막 로그인 수단을 잃는다.

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 토큰의 사용자 본인 계정의 연결만 해제한다.
- 해제해도 다른 세션(refresh token)은 폐기하지 않는다.

#### 9. 예시 요청/응답

```bash
curl -X DELETE "$ACCESS/api/auth/me/oauth-accounts/kakao" \
  -H 'Authorization: Bearer <access_token>' \
  -i
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: unlinkOAuthAccount`)
- 호출자: **호출자 미확인** (frontend 구현 예정, FruitionKR/Fruition-frontend#105)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-delete-api-auth-me-oauth-accounts-provider)

</details>

<a id="summary-post-api-auth-oauth-signup-consent"></a>
### `POST /api/auth/oauth/signup/consent`

| 항목 | 내용 |
|---|---|
| 목적 | 소셜 신규 가입 확정. 만 18세 이상 확인과 이용약관 동의를 받아 계정·기본 워크스페이스를 만들고 로그인시킵니다. |
| 입력 | **Body** — `OAuthSignupConsentRequest` `{ "signup_token", "age_confirmed", "terms_version", "marketing_opt_in", "code_verifier" }`(`code_verifier`는 데스크톱 로그인만) |
| 출력 | `200` 가입과 로그인 완료 — `LoginResponse`(access token, HttpOnly refresh 쿠키). 다른 탭이 먼저 가입한 계정이 MFA를 켰으면 `mfa_required`·`mfa_token`만 온다 |
| 조건 | 인증 불필요. `signup_token`은 10분 동안 한 번만 쓸 수 있다. [약관 동의 플래그](#약관-동의-플래그-applegalconsent-required)가 `false`면 `age_confirmed`·`terms_version`·`marketing_opt_in`을 생략해도 되고 동의 기록을 남기지 않는다. |
| 주요 오류 | `400` `CONSENT_REQUIRED` 만 18세 이상 확인·현재 이용약관 동의 없음(토큰은 소비하지 않아 다시 보낼 수 있다)<br>`401` `INVALID_SIGNUP_TOKEN` 토큰이 없거나 만료·사용됨, 또는 데스크톱 가입인데 `code_verifier`가 없거나 틀림(토큰은 소비된다). 소셜 로그인부터 다시 한다<br>`409` `EMAIL_REGISTERED` 토큰을 받은 뒤 동의 화면에 머무는 사이 같은 이메일로 다른 가입이 끝남 — `EmailRegisteredResponse`(`providers` 포함, 토큰은 소비된다). 기존 계정으로 로그인한 뒤 설정에서 연동한다 |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-oauth-signup-consent"></a>
### `POST /api/auth/oauth/signup/consent` 상세

#### 1. 흐름

1. 소셜 로그인 콜백에서 그 소셜 계정에 연결된 사용자가 없으면 계정을 만들지 않는다. 소셜 계정 정보(provider, provider 사용자 ID, 이메일, 이름)를
   Redis `oauth:signup:{token}`에 10분 보관하고, OAuth 콜백 주소에 `?code=` 대신 `?signup_token=`을 붙여 돌려보낸다.
   단, 같은 이메일의 계정이 이미 있으면(이메일 가입 `local`이든 다른 provider의 소셜 가입이든) 두 번째 계정이 생기지 않게 토큰을 발급하지 않고
   `?error=email_registered&providers=<쉼표 구분, 예: local,google>`을 붙여 돌려보낸다. 프론트는 "그 방법으로 로그인한 뒤 설정 > 계정에서 연동하세요"를 안내한다.
   이미 연결된 소셜 계정의 로그인(`?code=`)과 설정에서의 연동([연동 시작](#summary-post-api-auth-me-oauth-accounts-provider-link))은 영향을 받지 않는다.
2. 프론트는 동의 화면을 보여 주고 이 API를 부른다.
3. 서버는 동의를 확인한 뒤 토큰을 소비하고 계정·소셜 연결·기본 워크스페이스·동의 기록을 만들고 로그인 토큰을 준다.
   그사이 다른 탭에서 같은 소셜 계정으로 가입을 끝냈으면 그 계정으로 로그인시킨다. 그 계정이 MFA를 켰으면 토큰과 refresh 쿠키 대신
   일반 로그인처럼 `mfa_required: true`와 `mfa_token`을 돌려주고, `POST /api/auth/login/mfa`로 로그인을 마친다.

기존 회원의 소셜 로그인은 지금처럼 `?code=`로 돌아온다. 콜백 쿼리 정리: 기존 회원 `?code=`, 신규 가입 `?signup_token=`, 같은 이메일의 계정이 있어 가입 거절 `?error=email_registered&providers=`, 그 밖의 실패 `?error=oauth_failed`.

데스크톱 로그인([데스크톱 앱 로그인](#데스크톱-앱-로그인딥링크--pkce))이면 `signup_token`이 앱 딥링크로 오고, 토큰에 로그인 시작 때의
`code_challenge`가 묶인다. 이 API에 같은 로그인의 `code_verifier`를 함께 보내야 한다.

#### 2. Request body

```json
{
  "signup_token": "EXAMPLE-signup-token-not-real-000000000000",
  "age_confirmed": true,
  "terms_version": "2026-10-01",
  "marketing_opt_in": false,
  "code_verifier": "EXAMPLE-code-verifier-not-real-0000000000000"
}
```

- `code_verifier`: 데스크톱 로그인일 때만 보낸다. 웹 로그인 토큰에는 challenge가 없어 보내도 검사하지 않는다.
- `marketing_opt_in`: 생략하면 `false`.
- 동의 단계를 끈 동안(`app.legal.consent-required=false`)은 `{ "signup_token": "..." }`만 보내도 된다.

#### 3. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 처리: `src/main/java/fruition/access/user/service/OAuthUserService.java`(`startSignup`, `completeSignup`), `UserConsentService.java`
- 호출자: 없음(frontend 동의 화면 구현 예정)

[↑ 요약으로 돌아가기](#summary-post-api-auth-oauth-signup-consent)

</details>

<a id="summary-post-api-auth-oauth-exchange"></a>
### `POST /api/auth/oauth/exchange`

| 항목 | 내용 |
|---|---|
| 목적 | OAuth 로그인 성공 후 발급된 1회용 code를 access token과 HttpOnly refresh 쿠키로 교환합니다. 데스크톱 로그인 code는 `code_verifier`가 맞아야 교환됩니다. |
| 입력 | **Body** — `OAuthExchangeRequest` `{ "code", "code_verifier" }`(`code_verifier`는 데스크톱 로그인만) |
| 출력 | `200` 교환 성공 — `LoginResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `401` 유효하지 않거나 만료된 code, 또는 데스크톱 code인데 `code_verifier`가 없거나 틀림 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-oauth-exchange"></a>
### `POST /api/auth/oauth/exchange` 상세

#### 1. Method + Path

`POST /api/auth/oauth/exchange`

#### 2. 목적

OAuth 로그인 성공 후 발급된 1회용 code를 access token과 HttpOnly refresh 쿠키로 교환합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`OAuthExchangeRequest`)

```json
{
  "code": "string",
  "code_verifier": "string"
}
```

- `code_verifier`: 데스크톱 로그인일 때만 보낸다. 서버는 `BASE64URL(SHA256(code_verifier))`가 로그인 시작 때의 `code_challenge`와 같은지 본다.
  다르거나 없거나 형식(43~128자, `[A-Za-z0-9-._~]`)이 틀리면 `401 INVALID_OAUTH_CODE`이고 code는 소비된다. 웹 로그인 code에는 challenge가 없어 지금처럼 `code`만 보내면 된다.
- MFA를 켠 사용자는 웹과 똑같이 `mfa_required`와 `mfa_token`을 받고, 이어서 `POST /api/auth/login/mfa`로 로그인을 마친다.

#### 데스크톱 앱 로그인(딥링크 + PKCE)

데스크톱 앱은 시스템 브라우저로 소셜 로그인을 하고 앱 딥링크로 돌아온다. 딥링크를 다른 앱이 가로채도 `code_verifier`가 없으면
code를 쓸 수 없다.

1. 앱이 무작위 `code_verifier`(RFC 7636: 43~128자, `[A-Za-z0-9-._~]`)를 만들고 `code_challenge = BASE64URL(SHA256(code_verifier))`를 계산한다.
2. 시스템 브라우저로 `/oauth2/authorization/{provider}?client=desktop&code_challenge=<challenge>&code_challenge_method=S256`을 연다.
   - `code_challenge_method`는 `S256`만 받는다. `code_challenge`는 base64url 43~128자여야 한다.
   - challenge가 없거나 형식이 틀리거나 `plain`이면, 또는 `mode=link`와 함께 쓰면 인가를 시작하지 않고 `400`을 돌려준다.
3. 로그인이 끝나면 웹 주소 대신 `app.oauth.desktop-redirect-uri`(기본 `fruition://oauth/callback`)로 redirect한다.
   쿼리 이름은 웹과 같다: 기존 회원 `?code=`, 신규 가입 `?signup_token=`, 같은 이메일의 계정이 있어 가입 거절 `?error=email_registered&providers=`, 그 밖의 실패 `?error=oauth_failed`.
4. 앱이 `code`와 `code_verifier`로 이 API를 부른다. 신규 가입이면 `POST /api/auth/oauth/signup/consent`에 `code_verifier`를 함께 보낸다.

한계: 콜백에서 인가 요청을 꺼내기 전에 실패하면(브라우저 세션 만료로 `authorization_request_not_found`, `state` 누락 등)
서버가 데스크톱 로그인인지 알 수 없어 앱 딥링크 대신 웹 주소(`?error=oauth_failed`)로 보낸다. 앱은 딥링크를 기다리는 시간에
제한을 두고, 시간이 지나면 새 `code_verifier`로 로그인을 다시 시작한다.

refresh 쿠키는 `Max-Age`(기본 14일, `app.jwt.refresh-token-expiration-seconds`)가 붙은 영속 쿠키다. 앱을 다시 켜도
쿠키가 남아 있으면 `POST /api/auth/refresh`로 이어서 로그인된다.

#### 5. Response body

- HTTP `200`: 교환 성공
- Content-Type: `*/*` (`LoginResponse`)

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

- 응답의 `Set-Cookie`가 `fruition_refresh_token`을 `HttpOnly; SameSite=Strict`로 저장한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 유효하지 않거나 만료된 code | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/oauth/exchange" \
  -H 'Content-Type: application/json' \
  -c cookies.txt \
  --data '{"code":"<value>"}'
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: exchangeOAuthCode`)
- 호출자: Fruition-frontend `src/entities/user/api/login.ts:32`
- PKCE·딥링크: `src/main/java/fruition/access/security/oauth/OAuthLinkFlow.java`(시작 요청 검사), `OAuthExchangeCodeStore.java`(challenge 저장·verifier 검증), `handler/OAuth2AuthenticationSuccessHandler.java`, `handler/OAuth2AuthenticationFailureHandler.java`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-oauth-exchange)

</details>

<a id="summary-post-api-auth-password-reset"></a>
### `POST /api/auth/password-reset`

| 항목 | 내용 |
|---|---|
| 목적 | verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다. |
| 입력 | **Body** — `PasswordResetRequest` |
| 출력 | `204` 재설정 성공 |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 잘못된 요청 또는 유효하지 않은 토큰(`INVALID_VERIFICATION_TOKEN`), OAuth로만 가입된 이메일(`PASSWORD_LOGIN_UNAVAILABLE`) — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-password-reset"></a>
### `POST /api/auth/password-reset` 상세

#### 1. Method + Path

`POST /api/auth/password-reset`

#### 2. 목적

verification_token으로 본인 확인 후 비밀번호를 변경하고 기존 세션을 폐기합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`PasswordResetRequest`)

```json
{
  "email": "user@example.com",
  "new_password": "password1234",
  "verification_token": "EXAMPLE-verification-token-not-real-0000000"
}
```

#### 5. Response body

- HTTP `204`: 재설정 성공
- Body: 없음

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 또는 유효하지 않은 토큰 | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/password-reset" \
  -H 'Content-Type: application/json' \
  --data '{"email":"user@example.com","new_password":"password1234","verification_token":"EXAMPLE-verification-token-not-real-0000000"}'
```

```json
{
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: resetPassword`)
- 호출자: Fruition-frontend `src/entities/user/api/emailVerification.ts:82`
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-password-reset)

</details>

<a id="summary-post-api-auth-refresh"></a>
### `POST /api/auth/refresh`

| 항목 | 내용 |
|---|---|
| 목적 | HttpOnly refresh 쿠키를 검증하고 access token과 refresh 쿠키를 회전합니다. |
| 입력 | **Cookie** — `fruition_refresh_token` |
| 출력 | `200` 재발급 성공 — `LoginResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `401` 유효하지 않거나 만료된 refresh token — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-refresh"></a>
### `POST /api/auth/refresh` 상세

#### 1. Method + Path

`POST /api/auth/refresh`

#### 2. 목적

HttpOnly refresh 쿠키를 검증하고 access token과 refresh 쿠키를 회전합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Body: 없음
- Cookie: `fruition_refresh_token`(필수)

#### 5. Response body

- HTTP `200`: 재발급 성공
- Content-Type: `*/*` (`LoginResponse`)

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

- 응답의 `Set-Cookie`가 기존 refresh 쿠키를 회전한 값으로 교체한다.
- 같은 refresh token으로 동시에 여러 번 호출하면 한 번만 회전에 성공하고 나머지는 `401`이다. 회전 유예 시간은 없다.
  여러 탭·창이 함께 refresh하는 클라이언트는 요청을 하나로 모아야 한다.

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `401` | 유효하지 않거나 만료된 refresh token | `ErrorResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/refresh" \
  -b cookies.txt -c cookies.txt
```

```json
{
  "access_token": "string",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: refresh`)
- 호출자: Fruition-frontend `src/shared/api/client.ts:57` (`tryRefreshTokens`)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-post-api-auth-refresh)

</details>

<a id="summary-post-api-auth-signup"></a>
### `POST /api/auth/signup`

| 항목 | 내용 |
|---|---|
| 목적 | 이메일/비밀번호로 신규 사용자를 생성합니다. 만 18세 이상 확인과 현재 이용약관 동의가 있어야 합니다. 같은 이메일의 소셜 가입 계정이 있으면 `409`로 거절합니다. |
| 입력 | **Body** — `SignupRequest` |
| 출력 | `201` 회원가입 성공 — `SignupResponse` |
| 조건 | 인증 불필요<br>인증 없이 호출할 수 있다.<br>공개 API이므로 별도의 사용자 권한 검증이 없다. |
| 주요 오류 | `400` 잘못된 요청, 또는 `CONSENT_REQUIRED` 만 18세 이상 확인·현재 이용약관 동의 없음(인증 토큰은 소비하지 않는다) — `ErrorResponse`<br>`409` 이미 가입된 이메일: 이메일 가입 계정이면 `DUPLICATE_EMAIL` — `ErrorResponse`, 소셜 가입 계정만 있으면 `EMAIL_REGISTERED_WITH_OAUTH` — `EmailRegisteredWithOAuthResponse`(`oauth_providers` 포함, 인증 토큰은 소비하지 않는다) |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-post-api-auth-signup"></a>
### `POST /api/auth/signup` 상세

#### 1. Method + Path

`POST /api/auth/signup`

#### 2. 목적

이메일/비밀번호로 신규 사용자를 생성합니다.

#### 3. Auth 필요 여부

- 불필요
- 인증 없이 호출할 수 있다.

#### 4. Request body

- Parameters: 없음

- Content-Type: `application/json` (`SignupRequest`)

```json
{
  "display_name": "표시 이름",
  "email": "user@example.com",
  "password": "password1234",
  "verification_token": "EXAMPLE-verification-token-not-real-0000000",
  "age_confirmed": true,
  "terms_version": "2026-10-01",
  "marketing_opt_in": false
}
```

- `age_confirmed`: 만 18세 이상 확인. `true`여야 한다.
- `terms_version`: 화면에 보여 준 이용약관 버전. 서버의 현재 버전(`app.legal.terms-version`)과 같아야 한다.
- `marketing_opt_in`: 마케팅 수신 동의(선택). 생략하면 `false`.
- 동의 단계를 끈 동안(`app.legal.consent-required=false`)은 세 항목을 모두 생략해도 되고, 보내도 기록하지 않는다.
- 동의는 `user_consents`에 이용약관·처리방침 버전, 시각과 함께 쌓인다. 처리방침은 열람한 버전만 남기고 따로 동의받지 않는다.

#### 5. Response body

- HTTP `201`: 회원가입 성공
- Content-Type: `*/*` (`SignupResponse`)

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 6. Error response

| HTTP 상태 | 설명 | 응답 스키마 |
|---|---|---|
| `400` | 잘못된 요청 | `ErrorResponse` |
| `409` | 이미 가입된 이메일. `DUPLICATE_EMAIL`(이메일 가입 계정) | `ErrorResponse` |
| `409` | 같은 이메일의 소셜 가입 계정이 있음. `EMAIL_REGISTERED_WITH_OAUTH`, `oauth_providers` 포함. 인증번호 발급에서 먼저 걸리지만 API를 직접 불러도 같은 결과다 | `EmailRegisteredWithOAuthResponse` |

```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "details": [
      {
        "field": "email",
        "reason": "email은 필수입니다."
      }
    ],
    "message": "요청 형식이 올바르지 않습니다."
  }
}
```

#### 7. Pagination / filtering

- 페이지네이션: 지원하지 않음
- 필터링: 지원하지 않음

#### 8. 권한 규칙

- 공개 API이므로 별도의 사용자 권한 검증이 없다.

#### 9. 예시 요청/응답

```bash
curl -X POST "$ACCESS/api/auth/signup" \
  -H 'Content-Type: application/json' \
  --data '{"display_name":"표시 이름","email":"user@example.com","password":"password1234","verification_token":"EXAMPLE-verification-token-not-real-0000000"}'
```

```json
{
  "created_at": "2026-08-13T04:25:24.371948Z",
  "display_name": "표시 이름",
  "email": "user@example.com",
  "id": "user_3f1c8a6b52d7411e9c04ab5d2e7f6081"
}
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/AuthController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: signup`)
- 호출자: Fruition-frontend `src/entities/user/api/emailVerification.ts:63`
- 하위 호출: document-svc `POST /internal/workspaces/{workspaceId}/initial-note` — `src/main/java/fruition/access/workspace/service/WorkspaceService.java:148` → `src/main/java/fruition/access/workspace/service/DocumentInternalClient.java:57` (`app.internal.document-base-url`). 기본 워크스페이스 생성(`src/main/java/fruition/access/user/service/UserService.java:64`)을 거쳐 호출된다

[↑ 요약으로 돌아가기](#summary-post-api-auth-signup)

</details>
