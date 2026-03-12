// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Filters invalid CSV records in the chunk processing pipeline (reader → processor → writer).
 *
 * <p>Records with a null or blank name are filtered out. In Spring Batch, returning {@code null}
 * from {@link #process} signals the framework to skip writing this item — it increments the
 * step's {@code filterCount}, not the error/skip count. This is the standard filtering contract,
 * not a bug.
 */
public class CsvRecordProcessor implements ItemProcessor<CsvRecord, CsvRecord> {

    private static final Logger log = LoggerFactory.getLogger(CsvRecordProcessor.class);

    @Override
    public CsvRecord process(CsvRecord item) {
        if (item.name() == null || item.name().isBlank()) {
            log.debug("Filtered record with null/blank name: id={}", item.id());
            return null;
        }
        return item;
    }
}
