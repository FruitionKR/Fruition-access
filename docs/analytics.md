# Access 알파 분석 이벤트

`user_signed_up`, `member_invited`, `member_joined`를 제품 트랜잭션의 `analytics_outbox`에 기록합니다. 원문 계약의 이벤트명을 사용하며 `workspace_member_*` 별칭은 보내지 않습니다. 이메일 가입은 `method=email`, 소셜 신규 가입은 `google`/`naver`/`kakao`입니다. 로그인·계정 연결·기존 계정 반환에는 가입 이벤트가 없습니다.

초대자는 `member_invited.distinct_id`, 수락자는 `member_joined.distinct_id`이며 `workspace_id`와 `$groups.workspace`는 해당 초대의 workspace입니다. `invitation_id`와 `role=owner|member`만 추가합니다. 재발송은 기존 초대 행을 사용하므로 이벤트를 만들지 않고, 중복 수락은 행 잠금과 accepted 상태로 막습니다. 초대 발송 메일 실패 전에 이미 생성이 커밋됐다면 초대 생성 이벤트는 남습니다.

| 설정 | 기본값 | 동작 |
|---|---|---|
| `ANALYTICS_ENABLED` | `false` | false면 기록·relay 중지 |
| `POSTHOG_PROJECT_TOKEN` | 빈 값 | 공개 `phc_` 키만 relay 허용, 빈 값·개인 키는 전송 안 함 |
| `POSTHOG_HOST` | `https://us.i.posthog.com` | 서버 `/batch/` 목적지, 테스트는 loopback mock |
| `ANALYTICS_ENVIRONMENT` | `local` | `local`, `staging`, `production`만 허용 |
| `APP_VERSION` | `unknown` | 배포 SHA/릴리스 식별자, 영문·숫자·`._+-`, 128자 이하 |

잘못된 boolean·환경·버전·목적지 설정은 분석만 비활성화하며 제품 기동을 막지 않습니다. 설정 객체 문자열에는 토큰을 포함하지 않습니다.

UUID는 NAMESPACE_URL `6ba7b811-9dad-11d1-80b4-00c04fd430c8`와 `fruition.analytics.v1:{event}:{logicalKey}`의 UUIDv5입니다. 가입 키는 사용자 ID, 초대·수락 키는 초대 ID입니다. `uuid`와 `properties.event_id`가 같고, UTC 발생 시각·actor·workspace·버전·환경은 저장 뒤 재시도에서 바꾸지 않습니다. 이벤트별 속성 allowlist를 검사하며 이름·이메일·본문·질문·답변·URL·초대 토큰·오류 원문은 허용하지 않습니다.

기록은 Spring 동기 이벤트 리스너에서 같은 JPA 트랜잭션의 JDBC 연결과 savepoint를 사용합니다. INSERT 실패는 savepoint로 되돌리고 고정 코드 경고만 기록합니다. DB 연결 자체가 끊겨 제품 트랜잭션을 유지할 수 없는 장애까지 복구하는 것은 보장하지 않습니다. 기록 실패 이벤트는 영구 누락될 수 있으므로 `analytics_record_failed` 경고를 확인합니다.

relay는 `SmartLifecycle`이 시작·중지하는 전용 스레드에서 10초마다 최대 100건을 처리합니다. 별도 트랜잭션의 `FOR UPDATE SKIP LOCKED`로 복제본 간 중복 동시 처리를 막고 HTTP는 연결 2초·요청 5초 제한을 둡니다. 네트워크 작업은 제품 요청 경로에서 실행하지 않습니다. 실패는 1분부터 최대 1시간까지 지수 백오프하며 24회 실패 시 `dead=true`로 남깁니다. HTTP 응답 유실 또는 ACK 커밋 실패는 같은 UUID를 재전송하므로 판정 쿼리도 event_id 중복을 제거해야 합니다.

`sent_at` 행은 7일 후 지웁니다. dead 행은 자동 삭제하지 않으며 원인을 해결한 뒤 운영자가 기존 uuid·occurred_at·properties를 그대로 보존하고 attempts/dead/next_attempt_at만 재설정할 수 있습니다. 실제 활성화·배포·운영 데이터 재전송은 별도 작업입니다. 알파 중에는 pending/dead 건수, `analytics_record_failed`, `analytics_relay_failed` 고정 코드 경고와 수집 상태를 대조합니다.

검증은 JDK 21, PostgreSQL/Redis Testcontainers, loopback HTTP mock으로 수행합니다. 테스트 프로필은 외부 분석 설정을 상속하지 않으며 프로젝트 토큰 없이 실행됩니다.
