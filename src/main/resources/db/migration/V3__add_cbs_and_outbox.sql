ALTER TABLE transfers
    ADD COLUMN beneficiary_account VARCHAR(128),
    ADD COLUMN cbs_reference VARCHAR(128);

ALTER TABLE transfers
    ADD CONSTRAINT ck_transfers_external_beneficiary
    CHECK (
        transfer_type = 'INTERNAL'
        OR beneficiary_account IS NOT NULL
    );

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES transfers (id),
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0)
);

CREATE INDEX ix_outbox_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;
