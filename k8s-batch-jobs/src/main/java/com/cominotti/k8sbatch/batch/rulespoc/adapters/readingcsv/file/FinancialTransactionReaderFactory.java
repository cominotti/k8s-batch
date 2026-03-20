// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.readingcsv.file;

import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.Resource;

import java.math.BigDecimal;

/**
 * Factory for creating {@link FlatFileItemReader} instances configured for financial transaction
 * CSV files. Column order: {@code transactionId, accountId, amount, currency, timestamp}.
 *
 * <p>Follows the same factory pattern as
 * {@link com.cominotti.k8sbatch.batch.common.adapters.readingcsv.file.CsvRecordReaderFactory
 * CsvRecordReaderFactory}.
 */
public final class FinancialTransactionReaderFactory {

    private static final Logger log = LoggerFactory.getLogger(FinancialTransactionReaderFactory.class);

    private FinancialTransactionReaderFactory() {
    }

    /**
     * Creates a reader for the full CSV file (header skipped, all data lines).
     *
     * @param resource Spring {@link Resource} pointing to the CSV file
     * @return configured reader with column mapping for {@link FinancialTransaction} fields
     */
    public static FlatFileItemReader<FinancialTransaction> create(Resource resource) {
        log.debug("Creating financial transaction CSV reader | resource={}", resource.getDescription());
        return new FlatFileItemReaderBuilder<FinancialTransaction>()
                .name("financialTransactionReader")
                .resource(resource)
                .delimited()
                .names("transactionId", "accountId", "amount", "currency", "timestamp")
                .linesToSkip(1)
                .fieldSetMapper(fieldSet -> new FinancialTransaction(
                        fieldSet.readString("transactionId"),
                        fieldSet.readString("accountId"),
                        new BigDecimal(fieldSet.readString("amount")),
                        fieldSet.readString("currency"),
                        fieldSet.readString("timestamp")
                ))
                .build();
    }
}
