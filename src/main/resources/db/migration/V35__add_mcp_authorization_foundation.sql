-- MCP grants and policy are access-owned state, separate from web login and future SAS tables.
-- User/workspace FKs are intentionally absent: account/workspace deletion must retain a PURGED
-- authorization fence long enough for delayed internal work to fail closed.

CREATE TABLE mcp_client_policies (
    client_id  text                     PRIMARY KEY,
    enabled    boolean                  NOT NULL DEFAULT false,
    updated_at timestamp with time zone NOT NULL DEFAULT now()
);

CREATE TABLE mcp_workspace_policies (
    workspace_id varchar(255)             PRIMARY KEY,
    enabled      boolean                  NOT NULL DEFAULT false,
    generation   bigint                   NOT NULL DEFAULT 1,
    updated_at   timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT mcp_workspace_policies_generation_check CHECK (generation >= 1)
);

CREATE TABLE mcp_workspace_policy_scopes (
    workspace_id varchar(255) NOT NULL,
    scope        varchar(32)  NOT NULL,
    PRIMARY KEY (workspace_id, scope),
    CONSTRAINT mcp_workspace_policy_scopes_policy_fk
        FOREIGN KEY (workspace_id) REFERENCES mcp_workspace_policies(workspace_id) ON DELETE CASCADE,
    CONSTRAINT mcp_workspace_policy_scopes_scope_check
        CHECK (scope IN ('READ', 'WRITE_PROPOSE', 'WIKI_WRITE'))
);

CREATE TABLE mcp_grants (
    grant_id          uuid                     PRIMARY KEY,
    user_id           varchar(255)             NOT NULL,
    workspace_id      varchar(255)             NOT NULL,
    client_id         text                     NOT NULL,
    status            varchar(32)              NOT NULL,
    revocation_reason varchar(40),
    policy_generation bigint                   NOT NULL,
    created_at        timestamp with time zone NOT NULL,
    revoked_at        timestamp with time zone,
    CONSTRAINT mcp_grants_policy_generation_check CHECK (policy_generation >= 1),
    CONSTRAINT mcp_grants_status_check CHECK (
        status IN ('ACTIVE', 'USER_DISCONNECTED', 'ADMIN_REVOKED', 'SECURITY_REVOKED', 'PURGED')
    ),
    CONSTRAINT mcp_grants_status_reason_check CHECK (
        (status = 'ACTIVE' AND revocation_reason IS NULL AND revoked_at IS NULL)
        OR (status = 'USER_DISCONNECTED' AND revocation_reason = 'USER_DISCONNECT' AND revoked_at IS NOT NULL)
        OR (status = 'ADMIN_REVOKED'
            AND revocation_reason IN ('ADMIN_GRANT_REVOKE', 'WORKSPACE_POLICY_DISABLED')
            AND revoked_at IS NOT NULL)
        OR (status = 'SECURITY_REVOKED'
            AND revocation_reason IN ('REFRESH_REUSE', 'CLIENT_DISABLED')
            AND revoked_at IS NOT NULL)
        OR (status = 'PURGED'
            AND revocation_reason IN ('USER_PURGED', 'WORKSPACE_PURGED')
            AND revoked_at IS NOT NULL)
    )
);

CREATE TABLE mcp_grant_scopes (
    grant_id uuid        NOT NULL,
    scope    varchar(32) NOT NULL,
    PRIMARY KEY (grant_id, scope),
    CONSTRAINT mcp_grant_scopes_grant_fk
        FOREIGN KEY (grant_id) REFERENCES mcp_grants(grant_id) ON DELETE CASCADE,
    CONSTRAINT mcp_grant_scopes_scope_check
        CHECK (scope IN ('READ', 'WRITE_PROPOSE', 'WIKI_WRITE'))
);

CREATE INDEX idx_mcp_grants_user_status ON mcp_grants (user_id, status);
CREATE INDEX idx_mcp_grants_workspace_status ON mcp_grants (workspace_id, status);
CREATE INDEX idx_mcp_grants_client_status ON mcp_grants (client_id, status);

CREATE FUNCTION purge_user_mcp_grants() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE mcp_grants
    SET status = 'PURGED', revocation_reason = 'USER_PURGED', revoked_at = now()
    WHERE user_id = OLD.id AND status <> 'PURGED';
    RETURN OLD;
END;
$$;

CREATE TRIGGER purge_user_mcp_grants
BEFORE DELETE ON users
FOR EACH ROW EXECUTE FUNCTION purge_user_mcp_grants();

CREATE FUNCTION purge_workspace_mcp_authorization() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE mcp_workspace_policies
    SET generation = generation + CASE WHEN enabled THEN 1 ELSE 0 END,
        enabled = false,
        updated_at = now()
    WHERE workspace_id = OLD.id;

    UPDATE mcp_grants
    SET status = 'PURGED', revocation_reason = 'WORKSPACE_PURGED', revoked_at = now()
    WHERE workspace_id = OLD.id AND status <> 'PURGED';
    RETURN OLD;
END;
$$;

CREATE TRIGGER purge_workspace_mcp_authorization
BEFORE DELETE ON workspaces
FOR EACH ROW EXECUTE FUNCTION purge_workspace_mcp_authorization();
