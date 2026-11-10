-- 캠페인은 운영 시작 시 한 번 켠다. 배포만으로 내부 테스트 가입이 50자리를 소모하지 않는다.
CREATE TABLE beta_credit_campaign (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    starts_at TIMESTAMPTZ,
    enabled BOOLEAN NOT NULL DEFAULT false,
    allocated INTEGER NOT NULL DEFAULT 0 CHECK (allocated BETWEEN 0 AND 50)
);
INSERT INTO beta_credit_campaign (id) VALUES (1);
-- 재가입으로 자리를 다시 받지 않도록 탈퇴와 함께 지우지 않는다. 이메일 원문은 저장하지 않는다.
CREATE TABLE beta_credit_awards (
    user_id TEXT PRIMARY KEY,
    email_hash TEXT NOT NULL UNIQUE,
    slot INTEGER NOT NULL UNIQUE CHECK (slot BETWEEN 1 AND 50),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    amount_krw_milli BIGINT CHECK (amount_krw_milli > 0)
);
