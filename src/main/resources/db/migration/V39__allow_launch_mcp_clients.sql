-- 출시 대상 MCP 클라이언트의 고정 CIMD client_id를 허용한다(2026-11-10 각 문서를 받아 검증).
-- 운영자가 이미 정책 행을 만들어 둔 경우(허용/차단 모두) 그 결정을 덮어쓰지 않는다.
-- 제외: ChatGPT(private_key_jwt 필요), Cursor(공식 CIMD 아님), Gemini(사용자별 client_id).
INSERT INTO mcp_client_policies (client_id, enabled) VALUES
    ('https://claude.ai/oauth/mcp-oauth-client-metadata', true),
    ('https://claude.ai/oauth/claude-code-client-metadata', true),
    ('https://chatgpt.com/oauth/codex/client.json', true),
    ('https://vscode.dev/oauth/client-metadata.json', true),
    ('https://github.com/copilot/cli/client-metadata.json', true),
    ('https://zed.dev/oauth/client-metadata.json', true),
    ('https://antigravity.google/oauth/client-metadata.json', true)
ON CONFLICT (client_id) DO NOTHING;
