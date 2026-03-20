CREATE TABLE IF NOT EXISTS target_records (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255),
    amount     DECIMAL(10, 2),
    record_date DATE,
    source_file VARCHAR(500),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
