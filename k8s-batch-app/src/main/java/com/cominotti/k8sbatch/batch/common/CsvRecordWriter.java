package com.cominotti.k8sbatch.batch.common;

import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;

import javax.sql.DataSource;

public final class CsvRecordWriter {

    private CsvRecordWriter() {
    }

    public static JdbcBatchItemWriter<CsvRecord> create(DataSource dataSource, String sourceFile) {
        return new JdbcBatchItemWriterBuilder<CsvRecord>()
                .sql("""
                        INSERT INTO target_records (id, name, email, amount, record_date, source_file)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            name = VALUES(name),
                            email = VALUES(email),
                            amount = VALUES(amount),
                            record_date = VALUES(record_date),
                            source_file = VALUES(source_file)
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
