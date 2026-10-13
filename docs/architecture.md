# Access 구조

Access는 인증·OAuth·세션·워크스페이스·멤버·초대를 담당하는 Spring Boot 서비스입니다. 사용자 API 포트는 8081입니다.

- `src/main/java/fruition/access/`: 인증·워크스페이스 업무 코드
- `src/main/java/fruition/shared/`: 이 저장소가 직접 소유하는 JWT·공통 오류·멱등성 코드
- `src/main/resources/db/migration/`: access_db Flyway migration
- `api-specs/openapi.yaml`: 실행 코드와 비교하는 HTTP 계약

Document는 내부 API와 Redis 권한 projection으로 Access의 권한 정보를 사용합니다. workspace 생성 시 초기 노트 생성을 Document에 요청합니다. Access는 Document·AI 테이블을 직접 수정하지 않습니다.

탈퇴·워크스페이스 영구 삭제 때 Access가 데이터 파기 순서를 맡습니다. Document 파기 API(`/internal/purge/*`)가 성공한 뒤에 AI 파기 API(`/internal/ai/purge/*`, 주소 `AI_INTERNAL_BASE_URL`)를 이어서 호출하고, 둘 다 성공해야 파기 요청을 완료로 봅니다. 실패하면 순서 전체를 backoff로 다시 시도합니다. 근거는 [ADR-0018](adr/0018-data-purge-order.md)입니다.

자세한 인가·내부 토큰·네트워크 계약은 [공통 아키텍처](https://github.com/FruitionKR/Fruition-flatform/blob/main/docs/architecture.md), 각 endpoint는 [API 문서](api/README.md)를 따릅니다.
