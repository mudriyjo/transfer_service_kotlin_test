CREATE UNIQUE INDEX uq_transfers_scheduled_idempotency
    ON transfers (customer_id, transfer_type, idempotency_key)
    WHERE transfer_type = 'SCHEDULED';

CREATE INDEX ix_transfers_external_idempotency
    ON transfers (customer_id, idempotency_key)
    WHERE transfer_type = 'EXTERNAL';

CREATE INDEX ix_transfers_cbs_reference
    ON transfers (cbs_reference)
    WHERE cbs_reference IS NOT NULL;

CREATE INDEX ix_transfers_scheduled_at
    ON transfers (scheduled_at)
    WHERE transfer_type = 'SCHEDULED' AND status = 'SCHEDULED';
