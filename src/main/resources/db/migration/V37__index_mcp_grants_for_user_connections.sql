CREATE INDEX idx_mcp_grants_user_created_id
    ON mcp_grants (user_id, created_at DESC, grant_id DESC);
