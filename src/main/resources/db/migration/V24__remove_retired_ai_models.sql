-- 종료·폐기 일정이 잡힌 모델을 카탈로그에서 뺐으므로(#24) 이미 저장된 선택을 대체 모델로 옮긴다.
-- 같은 등급의 가장 싼 현행 모델로 옮긴다(공식 대체보다 비용 상승을 줄이되 사용자가 고른 등급을 유지).
UPDATE workspaces
SET ingest_lint_model = CASE ingest_lint_model
    WHEN 'gpt-5-nano' THEN 'gpt-6-luna'
    WHEN 'gpt-4.1-nano' THEN 'gpt-6-luna'
    WHEN 'gpt-5.4-nano' THEN 'gpt-6-luna'
    WHEN 'gpt-5-mini' THEN 'gpt-5.4-mini'
    WHEN 'o4-mini' THEN 'gpt-5.4-mini'
    WHEN 'gpt-5' THEN 'gpt-6.1-sol'
    WHEN 'o3' THEN 'gpt-6.1-sol'
END
WHERE ingest_lint_provider = 'openai'
  AND ingest_lint_model IN ('gpt-5-nano', 'gpt-4.1-nano', 'gpt-5.4-nano', 'gpt-5-mini', 'o4-mini', 'gpt-5', 'o3');
UPDATE workspaces
SET ingest_lint_model = CASE ingest_lint_model
    WHEN 'gemini-3.1-flash-lite' THEN 'gemini-3.5-flash-lite'
    WHEN 'gemini-3.7-flash' THEN 'gemini-3.8-flash'
    WHEN 'gemini-3.5-flash' THEN 'gemini-3.6-flash'
END
WHERE ingest_lint_provider = 'gemini'
  AND ingest_lint_model IN ('gemini-3.1-flash-lite', 'gemini-3.7-flash', 'gemini-3.5-flash');
ALTER TABLE workspaces
    ALTER COLUMN ingest_lint_model SET DEFAULT 'gemini-3.5-flash-lite';
