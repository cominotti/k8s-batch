package com.cominotti.k8sbatch.batch.common;

import org.springframework.batch.infrastructure.item.ItemProcessor;

public class CsvRecordProcessor implements ItemProcessor<CsvRecord, CsvRecord> {

    @Override
    public CsvRecord process(CsvRecord item) {
        if (item.name() == null || item.name().isBlank()) {
            return null; // skip records with no name
        }
        return item;
    }
}
