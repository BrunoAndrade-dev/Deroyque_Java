-- Existing records have no authenticated owner. Do not assign them to the next caller.
ALTER TABLE activation_requests
    ADD COLUMN owner_id VARCHAR(64) NOT NULL DEFAULT repeat('0', 64),
    ADD CONSTRAINT ck_activation_requests_owner CHECK (owner_id ~ '^[0-9a-f]{64}$');

CREATE INDEX idx_activation_requests_owner_created
    ON activation_requests (owner_id, created_at, id);
CREATE INDEX idx_activation_requests_owner_status
    ON activation_requests (owner_id, status, created_at, id);
CREATE INDEX idx_activation_requests_owner_customer
    ON activation_requests (owner_id, customer_id, created_at, id);
