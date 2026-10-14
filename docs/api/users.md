# Users API

[서비스 문서](../README.md) / [access-svc](README.md)

다른 사용자의 프로필 사진을 조회하는 API다. 사진 업로드·삭제는 [Auth](auth.md)의
`PUT /api/auth/me/avatar`·`DELETE /api/auth/me/avatar`다.

- API 수: 1
- 호출 연결: [access-svc 호출 연결 요약](README.md#호출-연결-요약) 참고

## API 목차

| API | 목적 |
|---|---|
| [`GET /api/users/{user_id}/avatar`](#summary-get-api-users-user-id-avatar) | 본인이거나 워크스페이스를 하나라도 같이 쓰는 멤버에게 프로필 사진 bytes를 반환합니다. |

## 한눈에 보기

<a id="summary-get-api-users-user-id-avatar"></a>
### `GET /api/users/{user_id}/avatar`

| 항목 | 내용 |
|---|---|
| 목적 | 본인이거나 워크스페이스를 하나라도 같이 쓰는 멤버에게 프로필 사진 bytes를 반환합니다. |
| 입력 | **Path** — `user_id`: `string`<br>**Query** — `v`: 캐시 무효화용(서버는 무시) |
| 출력 | `200` 이미지 bytes / `304` 캐시된 이미지 사용 |
| 조건 | 인증 필요<br>본인이거나 삭제되지 않은 워크스페이스를 하나라도 같이 쓰는 멤버여야 한다. |
| 주요 오류 | `404` 사용자를 볼 수 없거나 프로필 사진이 없음 — `ErrorResponse` |

<details>
<summary>상세 계약 보기</summary>

<a id="detail-get-api-users-user-id-avatar"></a>
### `GET /api/users/{user_id}/avatar` 상세

#### 1. Method + Path

`GET /api/users/{user_id}/avatar`

#### 2. 목적

`MeResponse.avatar_url`·`WorkspaceMemberResponse.avatar_url`이 가리키는 경로다. 레일·설정 메뉴·멤버 관리·알림 화면이
이니셜 상자 대신 사진을 보여 주는 데 쓴다.

#### 3. Auth 필요 여부

- 필요
- `Authorization: Bearer <access_token>`을 검증한다. `<img src>`로 바로 쓸 수 없으므로 프론트는 fetch로 받아 object URL을 만든다
  (워크스페이스 아이콘 이미지와 같다).

#### 4. Request body

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| path | `user_id` | `string` | 예 | 사용자 ID |
| query | `v` | `string` | 아니오 | `avatar_url`에 붙어 오는 이미지 SHA-256. 서버는 읽지 않으며 URL을 바꿔 캐시를 가르는 용도다 |

- 요청 본문 없음

#### 5. Response body

- HTTP `200`: 이미지 bytes. `Content-Type`은 업로드 시 판별한 형식이다
- HTTP `304`: `If-None-Match`가 현재 `ETag`와 같을 때

응답 헤더:

| 헤더 | 값 |
|---|---|
| `ETag` | 이미지 SHA-256(= `avatar_url`의 `v`) |
| `Cache-Control` | `no-cache, private` |
| `X-Content-Type-Options` | `nosniff` |

#### 6. Error response

| HTTP 상태 | 설명 | 코드 |
|---|---|---|
| `401` | access token이 없거나 유효하지 않음 | — |
| `404` | 사용자가 없거나, 본인이 아니면서 같이 쓰는 워크스페이스가 없음 | `USER_NOT_FOUND` |
| `404` | 사진을 설정하지 않음 | `USER_AVATAR_NOT_FOUND` |

#### 7. Pagination / filtering

- 지원하지 않음

#### 8. 권한 규칙

- 본인은 항상 볼 수 있다.
- 다른 사용자는 삭제되지 않은(`deleted_at IS NULL`) 워크스페이스를 하나라도 같이 쓰는 멤버일 때만 볼 수 있다.
  휴지통에 간 워크스페이스는 세지 않는다. 아니면 `404`로 존재를 숨긴다.
- `private` 캐시라 공유 캐시에 남지 않는다. `no-cache`로 매번 서버에 재검증하고 이미지가 같을 때만 `304`로 캐시를 재사용한다.
  사진을 바꾸면 `avatar_url`의 `v`가 바뀌어 URL 자체가 달라진다.
- 메타데이터와 바이너리를 `REPEATABLE_READ` 트랜잭션에서 같은 스냅샷으로 읽는다. 조회 중 교체·삭제되어도
  Content-Type·ETag·바이너리가 서로 다른 버전으로 섞이지 않는다.

#### 9. 예시 요청/응답

```bash
curl "$ACCESS/api/users/user_3f1c8a6b52d7411e9c04ab5d2e7f6081/avatar?v=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" \
  -H 'Authorization: Bearer <access_token>' \
  -o avatar.png
```

#### 10. 구현 파일

- 진입점: `src/main/java/fruition/access/user/controller/UserAvatarController.java`
- 기계 판독 계약: `api-specs/openapi.yaml` (`operationId: getAvatar`)
- 호출자: **호출자 미확인** (frontend 레일·설정·멤버 목록 구현 예정, FruitionKR/Fruition-access#25)
- 하위 호출: 없음

[↑ 요약으로 돌아가기](#summary-get-api-users-user-id-avatar)

</details>
