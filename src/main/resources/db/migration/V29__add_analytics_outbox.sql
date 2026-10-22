-- No product foreign keys: analytics failures are isolated with a JDBC savepoint.
CREATE TABLE analytics_outbox (
    uuid uuid PRIMARY KEY,
    event text NOT NULL,
    distinct_id text NOT NULL,
    occurred_at timestamptz NOT NULL,
    properties jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error_code text,
    sent_at timestamptz,
    dead boolean NOT NULL DEFAULT false
);
CREATE INDEX analytics_outbox_pending ON analytics_outbox(next_attempt_at)
    WHERE sent_at IS NULL AND NOT dead;
