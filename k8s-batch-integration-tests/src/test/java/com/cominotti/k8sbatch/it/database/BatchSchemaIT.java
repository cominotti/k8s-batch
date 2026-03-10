package com.cominotti.k8sbatch.it.database;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchSchemaIT extends AbstractIntegrationTest {

    @Test
    void shouldCreateBatchMetadataTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'BATCH_%'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "BATCH_JOB_INSTANCE",
                "BATCH_JOB_EXECUTION",
                "BATCH_JOB_EXECUTION_PARAMS",
                "BATCH_JOB_EXECUTION_CONTEXT",
                "BATCH_STEP_EXECUTION",
                "BATCH_STEP_EXECUTION_CONTEXT",
                "BATCH_STEP_EXECUTION_SEQ",
                "BATCH_JOB_EXECUTION_SEQ",
                "BATCH_JOB_INSTANCE_SEQ"
        );
    }

    @Test
    void shouldCreateBatchSequences() {
        Integer seqValue = jdbcTemplate.queryForObject(
                "SELECT ID FROM BATCH_JOB_INSTANCE_SEQ", Integer.class);
        assertThat(seqValue).isNotNull();
    }

    @Test
    void shouldHaveEmptyJobExecutionsInitially() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void shouldHaveEmptyStepExecutionsInitially() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION", Integer.class);
        assertThat(count).isZero();
    }
}
