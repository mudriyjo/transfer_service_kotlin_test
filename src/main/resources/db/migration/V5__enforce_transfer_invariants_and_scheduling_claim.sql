ALTER TABLE transfers
    ALTER COLUMN request_fingerprint SET NOT NULL,
    ADD COLUMN scheduling_claimed_by VARCHAR(128),
    ADD COLUMN scheduling_claimed_until TIMESTAMPTZ;

ALTER TABLE transfers
    ADD CONSTRAINT ck_transfers_shape
    CHECK (
        (
            transfer_type = 'INTERNAL'
            AND destination_account_id IS NOT NULL
            AND beneficiary_account IS NULL
            AND scheduled_at IS NULL
        )
        OR (
            transfer_type = 'EXTERNAL'
            AND destination_account_id IS NULL
            AND beneficiary_account IS NOT NULL
            AND scheduled_at IS NULL
        )
        OR (
            transfer_type = 'SCHEDULED'
            AND destination_account_id IS NULL
            AND beneficiary_account IS NOT NULL
            AND scheduled_at IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_transfers_failure_details
    CHECK (
        status = 'FAILED'
        OR (failure_code IS NULL AND failure_message IS NULL)
    ),
    ADD CONSTRAINT ck_transfers_scheduling_claim_pair
    CHECK (
        (scheduling_claimed_by IS NULL AND scheduling_claimed_until IS NULL)
        OR (scheduling_claimed_by IS NOT NULL AND scheduling_claimed_until IS NOT NULL)
    ),
    ADD CONSTRAINT ck_transfers_scheduling_claim_owner
    CHECK (
        scheduling_claimed_by IS NULL
        OR (
            transfer_type = 'SCHEDULED'
            AND LENGTH(TRIM(scheduling_claimed_by)) BETWEEN 1 AND 128
        )
    );

CREATE INDEX ix_transfers_scheduling_claim
    ON transfers (scheduled_at, scheduling_claimed_until)
    WHERE transfer_type = 'SCHEDULED' AND status = 'SCHEDULED';
