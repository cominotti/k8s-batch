// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction.adapters.persistingtransactions.jdbc;

import com.cominotti.k8sbatch.avro.EnrichedTransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;

import javax.sql.DataSource;
import java.sql.Timestamp;

/**
 * Factory for creating a {@link JdbcBatchItemWriter} that upserts enriched transactions into MySQL.
 *
 * <p>Uses MySQL 8's {@code ON DUPLICATE KEY UPDATE} with row alias for idempotent writes — if a
 * transaction with the same {@code transaction_id} (primary key) already exists, it is updated
 * rather than duplicated. Follows the same factory pattern as {@link
 * com.cominotti.k8sbatch.batch.common.adapters.persistingrecords.jdbc.CsvRecordWriter CsvRecordWriter}.
 */
public final class EnrichedTransactionWriter {

    private static final Logger log = LoggerFactory.getLogger(EnrichedTransactionWriter.class);

    private EnrichedTransactionWriter() {
    }

    /**
     * Creates an idempotent JDBC writer that upserts enriched transactions into the
     * {@code enriched_transactions} table using {@code ON DUPLICATE KEY UPDATE}.
     *
     * @param dataSource MySQL data source
     * @return configured writer ready to be used in a chunk step
     */
    public static JdbcBatchItemWriter<EnrichedTransactionEvent> create(DataSource dataSource) {
        log.debug("Creating enriched transaction JDBC batch writer");
        return new JdbcBatchItemWriterBuilder<EnrichedTransactionEvent>()
                // MySQL 8 row alias: "VALUES (...) AS new_row" names the incoming row so the
                // ON DUPLICATE KEY UPDATE clause can reference new values without the deprecated
                // VALUES() function syntax (deprecated in MySQL 8.0.20).
                .sql("""
                        INSERT INTO enriched_transactions
                            (transaction_id, account_id, amount, currency, exchange_rate,
                             amount_usd, risk_score, original_timestamp, processed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS new_row
                        ON DUPLICATE KEY UPDATE
                            account_id = new_row.account_id,
                            amount = new_row.amount,
                            currency = new_row.currency,
                            exchange_rate = new_row.exchange_rate,
                            amount_usd = new_row.amount_usd,
                            risk_score = new_row.risk_score,
                            original_timestamp = new_row.original_timestamp,
                            processed_at = new_row.processed_at
                        """)
                // Parameter indices must match the VALUES placeholder order above
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setString(1, item.getTransactionId());
                    ps.setString(2, item.getAccountId());
                    ps.setDouble(3, item.getAmount());
                    ps.setString(4, item.getCurrency());
                    ps.setDouble(5, item.getExchangeRate());
                    ps.setDouble(6, item.getAmountUsd());
                    ps.setString(7, item.getRiskScore());
                    ps.setLong(8, item.getTimestamp());
                    ps.setTimestamp(9, new Timestamp(item.getProcessedAt()));
                })
                .dataSource(dataSource)
                .build();
    }
}
