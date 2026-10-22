# Infrastructure 변경 이력

## 2026-10-22

- runtime과 Flyway migration JDBC URL에 `PGSSLROOTCERT` 전달을 추가해 RDS CA 번들과 `PGSSLMODE=verify-full`을 사용할 수 있게 했습니다. 실제 CA 마운트와 운영 환경 전환은 플랫폼 배포가 담당합니다.
