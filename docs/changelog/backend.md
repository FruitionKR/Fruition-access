# Backend 변경 이력

## 2026-10-22

- 알파 분석 `user_signed_up`(email/google/naver/kakao), `member_invited`, `member_joined`를 같은 제품 트랜잭션의 analytics outbox에 기록합니다. actor·workspace·UTC 발생 시각과 UUIDv5를 보존하며 초대 재발송·중복/동시 수락·기존 OAuth 계정 반환은 중복 집계하지 않습니다.
- JDBC savepoint로 분석 INSERT 오류를 격리하고 lifecycle 전용 relay에서 커밋된 이벤트만 전송합니다. 개인정보 없는 속성 allowlist, 독립 전송 timeout, SKIP LOCKED, 재시도·dead 처리와 7일 발송 이력 정리를 추가했습니다.
- PostgreSQL/loopback HTTP 통합 테스트에서 제품 롤백·SQL 실패 격리·전송 timeout/연결 실패·동시 relay·재시도 envelope·초대 동시 수락을 검증했습니다. 최종 전체 테스트 572개(실패·오류·스킵 0)와 bootJar가 통과했고, 수정하지 않은 origin/dev 전체 테스트 558개도 통과했습니다.

- Access JWT 발급을 RS256 개인키 서명으로 전환하고 현재·이전 공개키의 `kid`로 검증합니다. 운영에서는 RSA 키를 필수로 검사하며 2048비트 미만 키, 키 쌍 불일치, 허용하지 않은 알고리즘을 거부합니다.
- 기존 HMAC 토큰은 명시한 최대 24시간 전환 기한까지만 검증합니다. 전환 후 공유 비밀값을 제거해야 하며 Document 공개키 배포가 먼저 필요합니다.
- RSA 발급·이전 키·만료·issuer/audience·알고리즘 혼동·운영 필수 키·개발용 비밀값 거부 테스트를 추가했습니다.
- 검증: Java 21 전체 테스트 499개 통과(실패·오류·스킵 0), bootJar 패키징 통과.
- 무중단 전환을 위해 `JWT_SIGNING_MODE=legacy` 단계를 추가했습니다. RSA 검증을 먼저 전체 Access 복제본에 배포한 뒤 기본 `rsa` 발급으로 전환합니다. legacy 발급은 비밀값과 최대 24시간의 미래 기한이 필수이며, 기한이 지나면 자동 RSA 발급으로 바뀝니다. 기한 전 RSA 발급 전환과 기존 토큰 TTL 대기를 완료해야 합니다.
- 서명이 유효해도 exp가 없거나 sub가 비어 있는 토큰을 RSA·전환 HMAC 모두 거부하도록 필수 claim 검증을 보완했습니다.
