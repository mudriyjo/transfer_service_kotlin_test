CREATE TABLE bank_accounts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_bank_accounts_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    source_account_id UUID NOT NULL REFERENCES bank_accounts (id),
    destination_account_id UUID REFERENCES bank_accounts (id),
    transfer_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(128),
    scheduled_at TIMESTAMPTZ,
    failure_code VARCHAR(80),
    failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_transfers_type
        CHECK (transfer_type IN ('INTERNAL', 'EXTERNAL', 'SCHEDULED')),
    CONSTRAINT ck_transfers_status
        CHECK (status IN ('CREATED', 'SCHEDULED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfers_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_transfers_destination
        CHECK (
            (transfer_type = 'INTERNAL' AND destination_account_id IS NOT NULL)
            OR transfer_type <> 'INTERNAL'
        )
);

CREATE INDEX ix_bank_accounts_customer
    ON bank_accounts (customer_id, active);

CREATE INDEX ix_transfers_customer_created
    ON transfers (customer_id, created_at DESC);
