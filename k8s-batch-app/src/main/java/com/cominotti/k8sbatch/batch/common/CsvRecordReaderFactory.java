package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.time.LocalDate;

public final class CsvRecordReaderFactory {

    private static final Logger log = LoggerFactory.getLogger(CsvRecordReaderFactory.class);

    private CsvRecordReaderFactory() {
    }

    public static FlatFileItemReader<CsvRecord> create(Resource resource) {
        log.debug("Creating CSV reader for resource: {}", resource.getDescription());
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

    public static FlatFileItemReader<CsvRecord> create(String filePath) {
        return create(new FileSystemResource(filePath));
    }

    public static FlatFileItemReader<CsvRecord> createWithLineRange(
            Resource resource, int startLine, int endLine) {
        log.debug("Creating CSV reader with line range: startLine={} | endLine={} | resource={}",
                startLine, endLine, resource.getDescription());
        FlatFileItemReader<CsvRecord> reader = create(resource);
        reader.setCurrentItemCount(startLine);
        reader.setMaxItemCount(endLine);
        return reader;
    }
}
