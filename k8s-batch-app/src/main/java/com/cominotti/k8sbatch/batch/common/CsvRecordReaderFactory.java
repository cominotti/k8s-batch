// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.time.LocalDate;

/**
 * Factory for creating {@link FlatFileItemReader} instances configured for the CSV format used by
 * both ETL jobs. Centralizes reader configuration so that the file-range and multi-file jobs share
 * the same column mapping and header-skip logic.
 *
 * <p>A factory is used instead of a shared {@code @Bean} because {@code @StepScope} reader beans
 * are partition-specific and cannot be reused across job configurations.
 */
public final class CsvRecordReaderFactory {

    private static final Logger log = LoggerFactory.getLogger(CsvRecordReaderFactory.class);

    private CsvRecordReaderFactory() {
    }

    /**
     * Creates a reader for the full CSV file (header skipped, all data lines).
     *
     * @param resource Spring {@link Resource} pointing to the CSV file
     * @return configured reader with column mapping for {@link CsvRecord} fields
     */
    public static FlatFileItemReader<CsvRecord> create(Resource resource) {
        return new FlatFileItemReaderBuilder<CsvRecord>()
                .name("csvRecordReader")
                .resource(resource)
                .delimited()
                .names("id", "name", "email", "amount", "recordDate")
                .linesToSkip(1) // skip CSV header
                .fieldSetMapper(fieldSet -> new CsvRecord(
                        fieldSet.readLong("id"),
                        fieldSet.readString("name"),
                        fieldSet.readString("email"),
                        fieldSet.readBigDecimal("amount"),
                        LocalDate.parse(fieldSet.readString("recordDate"))
                ))
                .build();
    }

    /**
     * Convenience overload that accepts a filesystem path instead of a {@link Resource}.
     *
     * @param filePath absolute path to the CSV file
     * @return configured reader with column mapping for {@link CsvRecord} fields
     */
    public static FlatFileItemReader<CsvRecord> create(String filePath) {
        return create(new FileSystemResource(filePath));
    }

    /**
     * Creates a reader constrained to a line range within the CSV file, used by file-range
     * partitioning to split one file across multiple workers.
     *
     * @param resource  Spring {@link Resource} pointing to the CSV file
     * @param startLine 0-based start item index (after header skip)
     * @param endLine   exclusive end item index
     * @return reader that reads only items in {@code [startLine, endLine)}
     */
    public static FlatFileItemReader<CsvRecord> createWithLineRange(
            Resource resource, int startLine, int endLine) {
        log.debug("Creating CSV reader with line range: startLine={} | endLine={} | resource={}",
                startLine, endLine, resource.getDescription());
        FlatFileItemReader<CsvRecord> reader = create(resource);
        // These are 0-based item counts (after header skip), not raw line numbers.
        // The header line is already excluded by linesToSkip(1) in the base reader.
        reader.setCurrentItemCount(startLine);
        reader.setMaxItemCount(endLine);
        return reader;
    }
}
