package com.cominotti.k8sbatch.it;

import com.cominotti.k8sbatch.K8sBatchApplication;
import com.cominotti.k8sbatch.it.config.BatchTestJobConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = K8sBatchApplication.class)
@Import(BatchTestJobConfig.class)
@ActiveProfiles("integration-test")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public abstract class AbstractBatchIntegrationTest {

    @Autowired
    protected JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    protected JobRepository jobRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void verifySchemaReady() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'k8sbatch' AND table_name = 'target_records'",
                Integer.class);
        assertThat(tableCount).as("target_records table must exist (Flyway migration)").isEqualTo(1);
    }

    @AfterEach
    void cleanupBatchMetadata() {
        jobRepositoryTestUtils.removeJobExecutions();
    }

    @AfterEach
    void cleanupAppData() {
        jdbcTemplate.execute("DELETE FROM target_records");
    }

    protected String testResourcePath(String relativePath) {
        return getClass().getClassLoader().getResource(relativePath).getPath();
    }

    protected static JobParameters fileRangeJobParams(String inputFile) {
        return new JobParametersBuilder()
                .addString("batch.file-range.input-file", inputFile)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
    }

    protected static JobParameters multiFileJobParams(String inputDirectory) {
        return new JobParametersBuilder()
                .addString("batch.multi-file.input-directory", inputDirectory)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
    }
}
