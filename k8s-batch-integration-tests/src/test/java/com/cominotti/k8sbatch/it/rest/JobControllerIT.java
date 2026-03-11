package com.cominotti.k8sbatch.it.rest;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import com.cominotti.k8sbatch.it.config.BatchTestJobConfig;
import com.cominotti.k8sbatch.web.JobExecutionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

@Import(BatchTestJobConfig.class)
class JobControllerIT extends AbstractIntegrationTest {

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @AfterEach
    void cleanupBatchData() {
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.execute("DELETE FROM target_records");
    }

    @Test
    void shouldLaunchFileRangeJobAndComplete() {
        String inputFile = testResourcePath("test-data/csv/single/sample-10rows.csv");

        JobExecutionResponse launch = restClient().post()
                .uri("/api/jobs/fileRangeEtlJob")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("batch.file-range.input-file", inputFile))
                .retrieve()
                .body(JobExecutionResponse.class);

        assertThat(launch).isNotNull();
        assertThat(launch.executionId()).isGreaterThan(0);
        assertThat(launch.jobName()).isEqualTo("fileRangeEtlJob");

        // Poll until completed
        await().atMost(ofSeconds(60)).pollInterval(ofSeconds(1)).untilAsserted(() -> {
            JobExecutionResponse status = restClient().get()
                    .uri("/api/jobs/fileRangeEtlJob/executions/{id}", launch.executionId())
                    .retrieve()
                    .body(JobExecutionResponse.class);
            assertThat(status).isNotNull();
            assertThat(status.status()).isEqualTo("COMPLETED");
        });

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(10);
    }

    @Test
    void shouldReturnBadRequestForUnknownJob() {
        assertThatThrownBy(() -> restClient().post()
                .uri("/api/jobs/nonExistentJob")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(String.class))
                .isInstanceOf(HttpClientErrorException.class);
    }

    private String testResourcePath(String relativePath) {
        return Path.of(getClass().getClassLoader().getResource(relativePath).getPath()).toString();
    }
}
