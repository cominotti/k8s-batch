package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

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
