CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL REFERENCES transfers (id),
    account_id UUID NOT NULL REFERENCES bank_accounts (id),
    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_entries_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_ledger_entry_side UNIQUE (transfer_id, account_id, direction)
);

CREATE UNIQUE INDEX uq_transfers_internal_idempotency
    ON transfers (customer_id, transfer_type, idempotency_key)
    WHERE transfer_type = 'INTERNAL';

CREATE INDEX ix_ledger_entries_account_created
    ON ledger_entries (account_id, created_at DESC);
