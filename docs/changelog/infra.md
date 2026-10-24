# Infrastructure 변경 이력

## 2026-10-24

- 빌드 JDK·운영 JRE·CI를 Temurin 21.0.13+9로 고정해 10월 보안 수정과 갱신된 신뢰 저장소를 적용합니다. Docker Hub 태그 게시 지연으로 공식 Linux amd64/arm64 배포 파일을 고정 SHA-256으로 검증해 설치합니다.
- 기존 Java 설치를 제거한 뒤 교체하며, API·DB 스키마 변경은 없습니다. 검증 결과는 PR과 배포 기록에 남깁니다.

## 2026-10-22

- Access에 `ANALYTICS_ENABLED=false`, `POSTHOG_PROJECT_TOKEN`, `POSTHOG_HOST`(US 기본값), `ANALYTICS_ENVIRONMENT=local`, `APP_VERSION` 설정과 additive V29 analytics_outbox 마이그레이션을 추가했습니다. 공개 phc_ 토큰만 relay에 허용하고 잘못된 분석 설정은 제품 기동을 막지 않습니다.
- 테스트 프로필은 외부 분석 설정을 차단합니다. 실제 PostHog 전송·토큰 등록·배포는 수행하지 않았으며 활성화 절차와 실패/재시도 제약은 `docs/analytics.md`에 기록했습니다.

- runtime과 Flyway migration JDBC URL에 `PGSSLROOTCERT` 전달을 추가해 RDS CA 번들과 `PGSSLMODE=verify-full`을 사용할 수 있게 했습니다. 실제 CA 마운트와 운영 환경 전환은 플랫폼 배포가 담당합니다.
