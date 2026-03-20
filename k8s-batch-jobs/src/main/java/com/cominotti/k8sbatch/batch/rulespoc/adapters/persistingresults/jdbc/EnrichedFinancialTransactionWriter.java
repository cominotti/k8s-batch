// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.persistingresults.jdbc;

import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;

import javax.sql.DataSource;
import java.sql.Timestamp;

/**
 * Factory for creating a {@link JdbcBatchItemWriter} that upserts enriched financial transactions
 * into MySQL.
 *
 * <p>Uses MySQL 8's {@code ON DUPLICATE KEY UPDATE} with row alias for idempotent writes. Follows
 * the same pattern as
 * {@link com.cominotti.k8sbatch.batch.transaction.adapters.persistingtransactions.jdbc.EnrichedTransactionWriter
 * EnrichedTransactionWriter}.
 */
public final class EnrichedFinancialTransactionWriter {

    private static final Logger log = LoggerFactory.getLogger(EnrichedFinancialTransactionWriter.class);

    private EnrichedFinancialTransactionWriter() {
    }

    /**
     * Creates an idempotent JDBC writer that upserts enriched transactions into the
     * {@code rules_poc_enriched_transactions} table.
     *
     * @param dataSource MySQL data source
     * @return configured writer ready to be used in a chunk step
     */
    public static JdbcBatchItemWriter<EnrichedFinancialTransaction> create(DataSource dataSource) {
        log.debug("Creating enriched financial transaction JDBC writer");
        return new JdbcBatchItemWriterBuilder<EnrichedFinancialTransaction>()
                .sql("""
                        INSERT INTO rules_poc_enriched_transactions
                            (transaction_id, account_id, amount, currency, exchange_rate,
                             amount_usd, risk_score, compliance_flag, rules_engine,
                             original_timestamp, processed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new_row
                        ON DUPLICATE KEY UPDATE
                            account_id = new_row.account_id,
                            amount = new_row.amount,
                            currency = new_row.currency,
                            exchange_rate = new_row.exchange_rate,
                            amount_usd = new_row.amount_usd,
                            risk_score = new_row.risk_score,
                            compliance_flag = new_row.compliance_flag,
                            rules_engine = new_row.rules_engine,
                            original_timestamp = new_row.original_timestamp,
                            processed_at = new_row.processed_at
                        """)
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setString(1, item.transactionId());
                    ps.setString(2, item.accountId());
                    ps.setBigDecimal(3, item.amount());
                    ps.setString(4, item.currency());
                    ps.setBigDecimal(5, item.exchangeRate());
                    ps.setBigDecimal(6, item.amountUsd());
                    ps.setString(7, item.riskScore().name());
                    ps.setBoolean(8, item.complianceFlag());
                    ps.setString(9, item.rulesEngine());
                    ps.setString(10, item.originalTimestamp());
                    ps.setTimestamp(11, Timestamp.from(item.processedAt()));
                })
                .dataSource(dataSource)
                .build();
    }
}
