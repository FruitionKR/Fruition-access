# Backend 변경 이력

## 2026-10-22 운영 보안 hotfix

- 현재 운영 revision 기준으로 RSA JWT 발급·공개키 검증, 기한 제한 legacy 발급 전환만 적용했습니다. 이후 추가된 auth_time 기능과 DB migration은 포함하지 않습니다.
- 운영 RSA 필수, 개발용 secret 거부, 최대 24시간 legacy 전환, 잘못된 키·알고리즘·issuer·audience·만료 및 단계별 발급 테스트를 적용했습니다.
- 검증: 운영 기반 전체 clean test 381개 통과(실패·오류·스킵 0), bootJar 성공. 기존 DB migration·의존성·Dockerfile 변경 없음.
