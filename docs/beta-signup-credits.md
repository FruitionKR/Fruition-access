# 선착순 50명 자동 지급

이메일 인증 가입과 소셜 가입 모두 계정 생성 트랜잭션에서 같은 50개 슬롯을 배정한다.
가입이 롤백되면 슬롯도 되돌린다. 동일 계정 또는 정규화한 이메일의 재가입에는 중복 지급하지 않는다.
이메일 SHA-256과 지급 기록은 탈퇴 후에도 보존한다. 서로 다른 이메일의 동일인 여부는 판별하지 않는다.
50명 이후에도 가입은 가능하지만 무료 베타 크레딧은 없다. 일일 토큰 한도와 기존 사용자 잔액 면제는 변경하지 않는다.

가입 커밋 직후 document 내부 API로 500원 크레딧을 지급한다. 외부 장애는 가입 성공을 취소하지 않고
pending으로 남긴다. 30초 주기로 재시도하며, 실패한 항목은 1분 뒤부터 다시 시도한다.
문서 서비스가 지급한 뒤 응답을 잃어도 동일 슬롯의 재전송은 같은 금액을 반환한다.
GET /api/auth/me/beta-credit는 pending / granted / exhausted / ineligible와 amount_krw_milli를 반환한다.

## 활성화

1. document의 V118 및 내부 지급 API를 먼저 배포한다. access와 동일한 INTERNAL_CALLBACK_TOKEN이어야 한다.
2. access의 V38 및 이 코드를 배포하고 프론트엔드 지급 알림을 배포한다. 캠페인은 처음에는 비활성이다.
3. 신규 가입만 잠시 중지하고 진행 중 트랜잭션을 기다린다. 전환 시각을 기록한다.
4. document의 docs/credit-beta-rollout.md 절차로 **동일한 cutoff** 이전 계정을 모두 잔액 면제한다.
5. BILLING_ENFORCE=true 적용과 기존 계정 면제 검증을 마친 뒤 access DB에서 실행한다:

```sh
PGSERVICE=fruition-access psql -X -v ON_ERROR_STOP=1 \
  -v cutoff='실제 전환 시각(시간대 포함)' -f scripts/sql/activate-beta-signup-credits.sql
```

6. 가입을 재개한다. 별도 신청이나 운영자 지급 명령 없이 이후 성공한 가입부터 선착순으로 배정한다.
   기존 가입은 소급 배정하지 않는다. 테스트 가입도 활성화 후에는 실제 슬롯을 소모한다.

같은 cutoff 재실행은 allocated를 초기화하지 않는다. 중지하려면 enabled=false로 바꾼다.
이미 확보한 슬롯의 지급 재시도는 계속된다. 지급·배정 기록을 지워 슬롯을 재사용하지 않는다.
기존 가입을 이미 재개한 상태라면 적용 전에 cutoff와 면제 명단을 다시 대조해야 한다.

## 확인

```sql
SELECT starts_at, enabled, allocated, 50 - allocated AS remaining FROM beta_credit_campaign;
SELECT slot, user_id, assigned_at, delivered_at, amount_krw_milli
FROM beta_credit_awards ORDER BY slot;
SELECT count(*) AS pending FROM beta_credit_awards WHERE delivered_at IS NULL;
```

pending이 오래 유지되면 document 배포 버전, 내부 토큰, 서비스 연결과 지급 오류 로그를 확인한다.
모든 내부 금액은 milli-KRW이며 신규 지급액은 환율과 무관하게 500원(500,000 milli-KRW)이다.
기존 지급액은 유지하고 재시도도 document에 저장된 원래 금액을 반환한다.
아직 document에 지급 기록이 없는 대기 건은 변경 후 최초 지급 시 500원을 받는다.
