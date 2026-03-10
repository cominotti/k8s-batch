package com.cominotti.k8sbatch.it;

import com.cominotti.k8sbatch.it.config.BatchTestJobConfig;
import com.cominotti.k8sbatch.it.config.SharedContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import({SharedContainersConfig.class, BatchTestJobConfig.class})
@ActiveProfiles("integration-test")
public abstract class AbstractBatchIntegrationTest {

    @Autowired
    protected JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    protected JobRepository jobRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanupBatchMetadata() {
        jobRepositoryTestUtils.removeJobExecutions();
    }

    @AfterEach
    void cleanupAppData() {
        jdbcTemplate.execute("DELETE FROM target_records");
    }
}
