# 초대 로컬 E2E cookbook

공유 개발 스택과 운영 DB를 사용하지 않는다. 아래 절차는 별도 PostgreSQL/Redis와 합성 계정만 사용한다. 2026-10-22 Java 21 빌드로 아래 HTTP 시나리오를 새 일회용 PostgreSQL/Redis에서 실행했다. 신규/기존 계정 수락, 재발송, 이메일 불일치, 중복 수락, 자연 만료 후 재발송이 모두 기대 상태 코드로 통과했다.

## 1. 격리 실행

깨끗한 이 저장소의 별도 checkout에서 Java 21 `./gradlew bootJar`로 빌드한다. 다음 컨테이너 이름/포트가 이미 사용 중이면 새 이름/포트를 선택한다. 기존 컨테이너를 중지하거나 재사용하지 않는다.

```sh
rtk proxy docker run -d --name access-invite-e2e-pg -p 127.0.0.1:25432:5432 -e POSTGRES_DB=invite_e2e -e POSTGRES_USER=invite_e2e -e POSTGRES_PASSWORD=INSECURE-NOT-FOR-PRODUCTION postgres:16-alpine
rtk proxy docker run -d --name access-invite-e2e-redis -p 127.0.0.1:26379:6379 redis:7-alpine
rtk proxy docker exec access-invite-e2e-pg pg_isready -U invite_e2e -d invite_e2e
```

아래 앱은 별도 터미널에서 foreground로 실행한다. `env -i`로 실제 SMTP·PostHog·AWS 설정 상속을 없애고 `.env` 로딩도 끈다. `/tmp/access-invite-e2e.log`는 합성 인증번호/초대 링크를 포함하므로 원문을 공유하지 않는다. DB 권한 분리 대신 일회용 DB의 단일 계정을 사용하는 것은 이 절차에만 한정한다.

```sh
rtk proxy sh -c 'umask 077; env -i PATH="$PATH" HOME="$HOME" /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java -Dspring.dotenv.enabled=false -jar build/libs/access-svc-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.address=127.0.0.1 --server.port=28081 --management.server.address=127.0.0.1 --management.server.port=28083 --spring.datasource.url=jdbc:postgresql://127.0.0.1:25432/invite_e2e --spring.datasource.username=invite_e2e --spring.datasource.password=INSECURE-NOT-FOR-PRODUCTION --spring.flyway.user=invite_e2e --spring.flyway.password=INSECURE-NOT-FOR-PRODUCTION --spring.data.redis.host=127.0.0.1 --spring.data.redis.port=26379 --app.jwt.secret=jwt-secret-INSECURE-NOT-FOR-PRODUCTION-test --app.internal.callback-token=callback-token-INSECURE-NOT-FOR-PRODUCTION --app.auth.mfa.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= --app.internal.document-base-url=http://127.0.0.1:9 --app.ai.internal-base-url=http://127.0.0.1:9 --app.analytics.enabled=true --app.analytics.project-token= --app.analytics.host=http://127.0.0.1:18080 --app.analytics.environment=local --app.analytics.app-version=invitation-e2e --app.workspace.invitation.ttl-seconds=30 --app.workspace.invitation.accept-url=http://localhost:3000/invitations > /tmp/access-invite-e2e.log 2>&1'
```

JAR 파일명은 `settings.gradle`의 프로젝트 이름으로 결정되므로 `build/libs/*-SNAPSHOT.jar`의 실제 이름을 먼저 확인한다. SMTP host가 **미설정**이어야 console sender가 선택된다. 빈 host 설정을 추가하지 않는다. 빈 analytics project token은 기록을 허용하지만 relay를 막는다. `/actuator/health`가 준비된 뒤 진행한다. TTL 30초는 만료 검증용이며 기본값은 604800초(7일)이다.

## 2. API와 콘솔 토큰 처리

새 합성 owner, 기존 계정 역할의 invitee, 미가입 invitee를 각각 `owner-<run>@example.test`, `existing-<run>@example.test`, `new-<run>@example.test`로 만든다. 고정 코드 `9700`은 제거되었으므로 실제 console 인증번호를 사용한다. 다음 Python을 `/tmp/invitation-e2e.py`에 저장하고 `rtk proxy python3 /tmp/invitation-e2e.py`로 실행한다. 토큰과 응답 원문은 출력하지 않으며 결과에는 합성 ID만 남긴다.

```python
import json, re, secrets, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

BASE = "http://127.0.0.1:28081"
LOG = Path("/tmp/access-invite-e2e.log")
run = secrets.token_hex(6)
password = "Synthetic-" + secrets.token_hex(16)

def api(method, path, body=None, token=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                      headers=headers, method=method)
    try:
        with urlopen(request, timeout=60) as response:
            status, raw = response.status, response.read()
    except HTTPError as error:
        status, raw = error.code, error.read()
    assert status == expected, f"Unexpected HTTP status {status}; expected {expected}"
    return json.loads(raw) if raw else {}

def mail_value(pattern):
    for _ in range(50):
        matches = re.findall(pattern, LOG.read_text())
        if matches:
            return matches[-1]
        time.sleep(.1)
    raise AssertionError("Synthetic console mail not found")

def signup(email):
    verification = api("POST", "/api/auth/email-verifications",
                       {"email": email, "purpose": "signup"}, expected=202)
    code = mail_value(r"email=" + re.escape(email) + r" purpose=signup code=(\d+)")
    confirmed = api("POST", "/api/auth/email-verifications/" + verification["verification_id"] + "/confirm", {"code": code})
    user = api("POST", "/api/auth/signup", {"email": email, "password": password,
               "display_name": "Synthetic invitation E2E", "verification_token": confirmed["verification_token"],
               "age_confirmed": True, "terms_version": "2026-10-01", "marketing_opt_in": False}, expected=201)
    login = api("POST", "/api/auth/login", {"email": email, "password": password})
    return user["id"], login["access_token"]

owner_id, owner = signup("owner-" + run + "@example.test")
existing_email = "existing-" + run + "@example.test"
existing_id, existing = signup(existing_email)
workspace = api("POST", "/api/workspaces", {"name": "Synthetic invitation " + run}, owner, 201)["id"]

def invite(email):
    result = api("POST", "/api/workspaces/" + workspace + "/invitations",
                 {"email": email, "role": "MEMBER"}, owner, 201)
    token = mail_value(r"email=" + re.escape(email) + r" .*?acceptUrl=http://localhost:3000/invitations/([A-Za-z0-9_-]+)")
    return result["invitation_id"], token

def accept(token, user):
    return api("POST", "/api/invitations/" + token + "/accept", token=user)

# Existing account: reissue invalidates old token but preserves invitation identity.
inv_existing, old = invite(existing_email)
inv_again, current = invite(existing_email)
assert inv_existing == inv_again and old != current
api("GET", "/api/invitations/" + old, expected=404)
api("GET", "/api/invitations/" + current)
api("POST", "/api/invitations/" + current + "/accept", token=owner, expected=403)
accept(current, existing)
accept(current, existing)  # idempotent 200
api("GET", "/api/invitations/" + current, expected=409)

# New account: invite before signup, then authenticate with matching email.
new_email = "new-" + run + "@example.test"
inv_new, new_token = invite(new_email)
new_id, newcomer = signup(new_email)
accept(new_token, newcomer)
accept(new_token, newcomer)

# Expiry without DB mutation; no account/membership exists for expired invite.
expired_email = "expired-" + run + "@example.test"
expired_id, expired_user = signup(expired_email)
inv_expired, expired_token = invite(expired_email)
time.sleep(31)
api("GET", "/api/invitations/" + expired_token, expected=410)
api("POST", "/api/invitations/" + expired_token + "/accept", token=expired_user, expected=410)
inv_reissued, fresh = invite(expired_email)
assert inv_expired == inv_reissued and fresh != expired_token
api("GET", "/api/invitations/" + expired_token, expected=404)
accept(fresh, expired_user)
print(json.dumps({"workspace_id": workspace, "owner_id": owner_id,
    "invitation_ids": [inv_existing, inv_new, inv_expired],
    "invitee_ids": [existing_id, new_id, expired_id]}))
```

## 3. 논리 이벤트 중복 확인

위 결과의 합성 `workspace_id`를 아래 `ws_SYNTHETIC` 대신 넣고 **격리 컨테이너에서만** 조회한다.

```sh
rtk proxy docker exec access-invite-e2e-pg psql -U invite_e2e -d invite_e2e -c "SELECT event, properties->>'invitation_id' AS invitation_id, count(*) AS physical_rows, count(DISTINCT uuid) AS logical_events, bool_and(uuid::text = properties->>'event_id') AS stable_id, bool_and(sent_at IS NULL) AS not_relayed FROM analytics_outbox WHERE properties->>'workspace_id' = 'ws_SYNTHETIC' GROUP BY event, properties->>'invitation_id' ORDER BY invitation_id,event;"
```

기대 결과는 초대 ID 3개 × `member_invited`/`member_joined` 2개 = 6행이며 각 count는 1, stable_id/not_relayed는 true다. 재발송/중복 수락/만료된 링크 요청은 추가 이벤트를 만들지 않는다. `member_invited.distinct_id`는 owner, `member_joined.distinct_id`는 해당 invitee이며 `$groups.workspace`와 `workspace_id`도 일치해야 한다. role은 `member`다. UUID는 `uuid.uuid5(uuid.NAMESPACE_URL, "fruition.analytics.v1:" + event + ":" + invitation_id)`와 같아야 한다.

수집 mock을 별도로 사용할 경우에만 합성 `phc_` 토큰과 loopback host를 지정한다. HTTP 재시도로 동일 UUID가 여러 번 도착할 수 있으므로 mock 수신 개수 대신 `(event, properties.event_id)` distinct count를 확인한다. 실제 PostHog 목적지/토큰을 사용하지 않는다.

검증 후 앱을 Ctrl-C로 종료하고 위에서 새로 만든 두 컨테이너만 삭제한다. 합성 로그도 삭제한다. 공유 checkout, 공유 DB, AWS 및 실제 분석 데이터는 변경하지 않는다.
