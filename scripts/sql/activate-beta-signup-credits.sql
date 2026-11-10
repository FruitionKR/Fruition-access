-- access DB. 가입을 잠시 중지한 상태에서 기존 사용자 면제와 동일한 cutoff로 실행한다.
-- psql -X -v ON_ERROR_STOP=1 -v cutoff='실제 전환 시각' -f scripts/sql/activate-beta-signup-credits.sql
\set ON_ERROR_STOP on
BEGIN;
SELECT id FROM beta_credit_campaign WHERE id = 1 FOR UPDATE;
CREATE TEMP TABLE beta_activation_cutoff ON COMMIT DROP AS SELECT :'cutoff'::timestamptz AS value;
DO $$
BEGIN
    IF (SELECT value > now() FROM beta_activation_cutoff) THEN
        RAISE EXCEPTION 'cutoff must not be in the future';
    END IF;
    IF EXISTS (SELECT 1 FROM beta_credit_campaign c, beta_activation_cutoff t
               WHERE c.starts_at IS NOT NULL AND c.starts_at <> t.value) THEN
        RAISE EXCEPTION 'campaign cutoff cannot change once activated';
    END IF;
END $$;
UPDATE beta_credit_campaign SET starts_at = (SELECT value FROM beta_activation_cutoff), enabled = true WHERE id = 1;
COMMIT;
SELECT starts_at, enabled, allocated, 50 - allocated AS remaining FROM beta_credit_campaign WHERE id = 1;
