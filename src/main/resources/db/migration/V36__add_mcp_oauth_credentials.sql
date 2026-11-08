-- OAuth credentials are deliberately separate from SAS's generic schema: MCP codes bind
-- access-owned grant/resource/policy state, and refresh reuse needs durable family history.

CREATE TABLE mcp_oauth_pending_authorizations (
    pending_id       uuid                     PRIMARY KEY,
    user_id          varchar(255)             NOT NULL,
    session_id       varchar(255)             NOT NULL,
    client_id        text                     NOT NULL,
    client_name      text,
    redirect_uri     text                     NOT NULL,
    redirect_host    text                     NOT NULL,
    resource         text                     NOT NULL,
    scopes           text                     NOT NULL,
    code_challenge   varchar(128)             NOT NULL,
    state            text,
    expires_at       timestamp with time zone NOT NULL,
    decided_at       timestamp with time zone,
    decision         varchar(16),
    created_at       timestamp with time zone NOT NULL,
    CONSTRAINT mcp_oauth_pending_decision_check
        CHECK (decision IS NULL OR decision IN ('APPROVED', 'DENIED'))
);

CREATE TABLE mcp_oauth_authorization_codes (
    code_hash         bytea                    PRIMARY KEY,
    grant_id          uuid                     NOT NULL REFERENCES mcp_grants(grant_id),
    user_id           varchar(255)             NOT NULL,
    workspace_id      varchar(255)             NOT NULL,
    client_id         text                     NOT NULL,
    redirect_uri      text                     NOT NULL,
    resource          text                     NOT NULL,
    scopes            text                     NOT NULL,
    code_challenge    varchar(128)             NOT NULL,
    policy_generation bigint                   NOT NULL CHECK (policy_generation >= 1),
    expires_at        timestamp with time zone NOT NULL,
    consumed_at       timestamp with time zone,
    created_at        timestamp with time zone NOT NULL
);

CREATE TABLE mcp_oauth_refresh_families (
    family_id         uuid                     PRIMARY KEY,
    grant_id          uuid                     NOT NULL REFERENCES mcp_grants(grant_id),
    user_id           varchar(255)             NOT NULL,
    workspace_id      varchar(255)             NOT NULL,
    client_id         text                     NOT NULL,
    resource          text                     NOT NULL,
    scopes            text                     NOT NULL,
    policy_generation bigint                   NOT NULL CHECK (policy_generation >= 1),
    status            varchar(16)              NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    current_token_id  uuid,
    created_at        timestamp with time zone NOT NULL,
    revoked_at        timestamp with time zone
);

CREATE TABLE mcp_oauth_refresh_tokens (
    token_id    uuid                     PRIMARY KEY,
    family_id   uuid                     NOT NULL REFERENCES mcp_oauth_refresh_families(family_id) ON DELETE CASCADE,
    token_hash  bytea                    NOT NULL UNIQUE,
    sequence    bigint                   NOT NULL CHECK (sequence >= 1),
    status      varchar(16)              NOT NULL CHECK (status IN ('ACTIVE', 'USED', 'REVOKED')),
    issued_at   timestamp with time zone NOT NULL,
    expires_at  timestamp with time zone NOT NULL,
    used_at     timestamp with time zone,
    UNIQUE (family_id, sequence)
);

ALTER TABLE mcp_oauth_refresh_families
    ADD CONSTRAINT mcp_oauth_refresh_current_fk
    FOREIGN KEY (current_token_id) REFERENCES mcp_oauth_refresh_tokens(token_id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_mcp_oauth_pending_user ON mcp_oauth_pending_authorizations(user_id, expires_at);
CREATE INDEX idx_mcp_oauth_codes_grant ON mcp_oauth_authorization_codes(grant_id);
CREATE INDEX idx_mcp_oauth_refresh_grant ON mcp_oauth_refresh_families(grant_id, status);

CREATE FUNCTION remove_user_mcp_credentials() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM mcp_oauth_pending_authorizations WHERE user_id = OLD.id;
    DELETE FROM mcp_oauth_authorization_codes WHERE user_id = OLD.id;
    DELETE FROM mcp_oauth_refresh_families WHERE user_id = OLD.id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER remove_user_mcp_credentials
BEFORE DELETE ON users
FOR EACH ROW EXECUTE FUNCTION remove_user_mcp_credentials();

CREATE FUNCTION remove_workspace_mcp_credentials() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM mcp_oauth_authorization_codes WHERE workspace_id = OLD.id;
    DELETE FROM mcp_oauth_refresh_families WHERE workspace_id = OLD.id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER remove_workspace_mcp_credentials
BEFORE DELETE ON workspaces
FOR EACH ROW EXECUTE FUNCTION remove_workspace_mcp_credentials();
