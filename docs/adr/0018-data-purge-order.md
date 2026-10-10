# ADR-0018: 탈퇴·워크스페이스 영구 삭제 시 데이터 파기 순서

- 상태: 적용됨
- 관련: [#29](https://github.com/FruitionKR/Fruition-access/issues/29), FruitionKR/Fruition-document#88, FruitionKR/Fruition-ai#61, [architecture](../architecture.md)

## 맥락

회원 탈퇴나 워크스페이스 영구 삭제 때 document는 문서·채팅·회의·파일을, ai는 위키·스킬·에이전트 기록·질의 원문이 담긴 작업 기록·S3 위키 객체를 지워야 한다. ai 파기 API는 만들어졌지만 호출하는 곳이 없어 AI 데이터가 남았다.

두 서비스는 Kafka로 AI 작업 명령과 결과를 주고받는다. ai를 먼저 지우거나 동시에 지우면, document의 outbox에 아직 보내지 않은 명령이 남은 채 ai 파기가 끝나 파기한 범위의 데이터가 다시 생길 수 있다.

## 결정

access의 `DataPurgeRequestJob`(탈퇴)과 `ExpiredRecordCleanupJob`(휴지통 만료)이 **document 파기가 성공한 뒤에** ai 파기를 호출한다.

- 사용자: `POST /internal/ai/purge/users` `{"user_id"}`, 워크스페이스: `POST /internal/ai/purge/workspaces` `{"workspace_ids"}`. 인증은 기존 내부 토큰(`X-Internal-Token`)이다.
- document 파기는 미발행 AI 명령(outbox)과 실행 기록을 먼저 지운다. 그래서 ai가 파기를 끝낼 때 보내지 않은 명령이 남지 않는다. 이미 Kafka로 나간 메시지는 ai가 `ai_purged_scopes`로 거절하고, document는 실행 기록이 없는 결과를 건너뛴다(Fruition-document#88).
- 둘 다 성공해야 파기 요청을 완료로 표시한다. ai가 실패하면 요청을 남기고 기존 지수 backoff로 다시 시도한다.
- 단계별 진행은 저장하지 않고 재시도 때 순서 전체(document → ai)를 다시 호출한다. 두 API 모두 멱등이라 이미 지운 범위는 0건으로 끝나므로, `data_purge_requests`에 컬럼을 늘리는 것보다 단순하다.
- ai 주소는 `app.ai.internal-base-url`(`AI_INTERNAL_BASE_URL`), 응답 대기는 S3 삭제를 고려해 기본 120초(`app.ai.purge-read-timeout`)다.

## 대안과 기각 사유

- **A. document가 ai를 동기 호출한다.** document가 ai를 알아야 해 서비스 사이 결합이 생기고, document 파기 요청이 S3 삭제 시간만큼 길어진다. 기각.
- **C. Kafka 파기 이벤트를 발행한다.** access는 ai의 완료 여부를 알 수 없어 재시도 판단을 못 한다. 새 토픽과 consumer가 필요하고, 파기 대상 식별자가 Kafka에 더 오래 남는다. 기각.

## 결과

- access는 ai 엔드포인트에 닿아야 하므로 네트워크 정책 허용과 `AI_INTERNAL_BASE_URL` 설정이 필요하다(Fruition-flatform B1).
- 재시도는 순서 전체이며 멱등이다. ai가 계속 실패하면 요청이 남아 최대 6시간 간격으로 재시도하고 `last_error`에 기록된다.
- 파기 뒤에도 S3 구버전·로그는 30일 안에, Kafka 메시지는 72시간 안에 만료된다(Fruition-flatform B1).
- 배포는 마지막에 한다: Fruition-flatform B1 → Fruition-ai 파기 수정 → Fruition-document#88 → 이 변경.
