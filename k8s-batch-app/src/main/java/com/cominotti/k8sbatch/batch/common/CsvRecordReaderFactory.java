package com.cominotti.k8sbatch.batch.common;

import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

public final class CsvRecordReaderFactory {

    private CsvRecordReaderFactory() {
    }

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
                        fieldSet.readDate("recordDate", "yyyy-MM-dd")
                                .toInstant()
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                ))
                .build();
    }

    public static FlatFileItemReader<CsvRecord> create(String filePath) {
        return create(new FileSystemResource(filePath));
    }

    public static FlatFileItemReader<CsvRecord> createWithLineRange(
            Resource resource, int startLine, int endLine) {
        FlatFileItemReader<CsvRecord> reader = create(resource);
        reader.setCurrentItemCount(startLine);
        reader.setMaxItemCount(endLine);
        return reader;
    }
}
