// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;

import javax.sql.DataSource;

public final class CsvRecordWriter {

    private static final Logger log = LoggerFactory.getLogger(CsvRecordWriter.class);

    private CsvRecordWriter() {
    }

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
