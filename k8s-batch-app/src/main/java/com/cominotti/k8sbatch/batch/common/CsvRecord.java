// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data transfer object for one CSV row. Column order matches the file header:
 * {@code id, name, email, amount, recordDate}. The {@code id} field is the natural key used for
 * upsert deduplication in {@link CsvRecordWriter}.
 */
public record CsvRecord(
        Long id,
        String name,
        String email,
        BigDecimal amount,
        LocalDate recordDate
) {
}
