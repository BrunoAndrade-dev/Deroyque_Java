CREATE TABLE activation_requests (
    id UUID PRIMARY KEY,
    external_id VARCHAR(100) NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    product_code VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_activation_requests_external_id UNIQUE (external_id),
    CONSTRAINT ck_activation_requests_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_activation_requests_dates CHECK (updated_at >= created_at),
    CONSTRAINT ck_activation_requests_external_id CHECK (length(btrim(external_id)) > 0),
    CONSTRAINT ck_activation_requests_customer_id CHECK (length(btrim(customer_id)) > 0),
    CONSTRAINT ck_activation_requests_product_code CHECK (length(btrim(product_code)) > 0)
);

CREATE INDEX idx_activation_requests_status_created_at
    ON activation_requests (status, created_at, id);

CREATE INDEX idx_activation_requests_customer_created_at
    ON activation_requests (customer_id, created_at, id);
