-- 2026-10-18 비용 정책: 카탈로그를 provider마다 가장 싼 모델 하나로 줄였고,
-- 워크스페이스 공용 설정 ingest_lint에는 Claude를 허용하지 않는다(Claude는 채팅에서 직접 고를 때만).
-- 허용 목록 밖 값은 openai → gpt-6-luna, gemini → gemini-3.5-flash-lite, 그 밖(claude 포함) → gemini/gemini-3.5-flash-lite로 옮긴다.
UPDATE workspaces
SET ingest_lint_model = 'gpt-6-luna'
WHERE ingest_lint_provider = 'openai'
  AND ingest_lint_model <> 'gpt-6-luna';
UPDATE workspaces
SET ingest_lint_model = 'gemini-3.5-flash-lite'
WHERE ingest_lint_provider = 'gemini'
  AND ingest_lint_model <> 'gemini-3.5-flash-lite';
UPDATE workspaces
SET ingest_lint_provider = 'gemini',
    ingest_lint_model = 'gemini-3.5-flash-lite'
WHERE ingest_lint_provider NOT IN ('openai', 'gemini');
