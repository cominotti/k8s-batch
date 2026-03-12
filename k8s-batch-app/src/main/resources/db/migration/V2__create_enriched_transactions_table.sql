CREATE TABLE IF NOT EXISTS enriched_transactions (
    transaction_id     VARCHAR(36) NOT NULL PRIMARY KEY,
    account_id         VARCHAR(36) NOT NULL,
    amount             DECIMAL(15, 2) NOT NULL,
    currency           VARCHAR(3) NOT NULL,
    exchange_rate      DECIMAL(15, 6) NOT NULL,
    amount_usd         DECIMAL(15, 2) NOT NULL,
    risk_score         VARCHAR(10) NOT NULL,
    original_timestamp BIGINT NOT NULL,
    processed_at       TIMESTAMP(3) NOT NULL,
    created_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
