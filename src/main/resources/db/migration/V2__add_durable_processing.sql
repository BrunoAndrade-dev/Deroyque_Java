ALTER TABLE activation_requests
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN processing_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN processing_token UUID,
    ADD COLUMN last_error VARCHAR(64),
    ADD COLUMN correlation_id VARCHAR(100);

UPDATE activation_requests SET next_attempt_at = created_at WHERE status = 'PENDING';
UPDATE activation_requests SET attempt_count = 1 WHERE status <> 'PENDING';
UPDATE activation_requests
    SET processing_started_at = updated_at, processing_token = id WHERE status = 'PROCESSING';
UPDATE activation_requests SET correlation_id = id::text;

ALTER TABLE activation_requests
    ALTER COLUMN correlation_id SET NOT NULL,
    ADD CONSTRAINT ck_activation_requests_attempt_count CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_activation_requests_processing CHECK (
        (status = 'PROCESSING' AND processing_started_at IS NOT NULL AND processing_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND processing_started_at IS NULL AND processing_token IS NULL)),
    ADD CONSTRAINT ck_activation_requests_next_attempt CHECK (
        (status = 'PENDING' AND next_attempt_at IS NOT NULL)
        OR (status <> 'PENDING' AND next_attempt_at IS NULL));

CREATE INDEX idx_activation_requests_due
    ON activation_requests (next_attempt_at, created_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_activation_requests_abandoned
    ON activation_requests (processing_started_at, id) WHERE status = 'PROCESSING';
