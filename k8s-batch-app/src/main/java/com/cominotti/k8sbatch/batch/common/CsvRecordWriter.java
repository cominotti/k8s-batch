// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;

import javax.sql.DataSource;

/**
 * Factory for creating a {@link JdbcBatchItemWriter} that upserts CSV records into MySQL.
 *
 * <p>Uses MySQL 8's {@code ON DUPLICATE KEY UPDATE} with row alias for idempotent writes — if a
 * record with the same {@code id} (primary key) already exists, it is updated rather than
 * duplicated. The {@code source_file} column tracks which partition wrote each row.
 */
public final class CsvRecordWriter {

    private static final Logger log = LoggerFactory.getLogger(CsvRecordWriter.class);

    private CsvRecordWriter() {
    }

    /**
     * Creates an idempotent JDBC writer that upserts CSV records into the {@code target_records}
     * table using {@code ON DUPLICATE KEY UPDATE}.
     *
     * @param dataSource MySQL data source
     * @param sourceFile identifier written to the {@code source_file} column for partition traceability
     * @return configured writer ready to be used in a chunk step
     */
    public static JdbcBatchItemWriter<CsvRecord> create(DataSource dataSource, String sourceFile) {
        log.debug("Creating JDBC batch writer | sourceFile={}", sourceFile);
        return new JdbcBatchItemWriterBuilder<CsvRecord>()
                .sql("""
                        INSERT INTO target_records (id, name, email, amount, record_date, source_file)
                        VALUES (?, ?, ?, ?, ?, ?) AS new_row
                        ON DUPLICATE KEY UPDATE
                            name = new_row.name,
                            email = new_row.email,
                            amount = new_row.amount,
                            record_date = new_row.record_date,
                            source_file = new_row.source_file
                        """)
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setLong(1, item.id());
                    ps.setString(2, item.name());
                    ps.setString(3, item.email());
                    ps.setBigDecimal(4, item.amount());
                    ps.setObject(5, item.recordDate());
                    ps.setString(6, sourceFile);
                })
                .dataSource(dataSource)
                .build();
    }
}
