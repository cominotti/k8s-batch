// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.batch;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.client.BatchAppClient.JobResponse;
import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import io.fabric8.kubernetes.api.model.Pod;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the standalone profile, which deploys only the app and MySQL (no Kafka).
 * Partitioning is handled in-process via {@code TaskExecutorPartitionHandler} instead of
 * Kafka-based remote partitioning. Verifies that no Kafka pods are created, and that both
 * the file-range and multi-file ETL jobs complete successfully without messaging infrastructure.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StandaloneProfileE2E extends AbstractE2ETest {

    @Override
    protected String valuesFile() {
        return "e2e-standalone.yaml";
    }

    @Test
    @Order(1)
    void shouldDeployWithoutKafkaPods() {
        List<Pod> pods = K3sClusterManager.client().pods()
                .inNamespace(K3sClusterManager.namespace())
                .withLabel("app.kubernetes.io/instance", K3sClusterManager.releaseName())
                .list().getItems();

        // Should have app + mysql, but no kafka
        boolean hasKafka = pods.stream()
                .anyMatch(p -> p.getMetadata().getName().contains("kafka"));
        assertThat(hasKafka).as("No Kafka pods should exist in standalone mode").isFalse();

        // App and MySQL should be present
        boolean hasApp = pods.stream()
                .anyMatch(p -> p.getMetadata().getLabels() != null &&
                        "app".equals(p.getMetadata().getLabels().get("app.kubernetes.io/component")));
        boolean hasMysql = pods.stream()
                .anyMatch(p -> p.getMetadata().getName().contains("mysql"));
        assertThat(hasApp).as("App pod should exist").isTrue();
        assertThat(hasMysql).as("MySQL pod should exist").isTrue();
    }

    @Test
    @Order(2)
    void shouldRunFileRangeJobInStandaloneMode() throws Exception {
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "fileRangeEtlJob",
                Map.of("batch.file-range.input-file", "/data/test/sample-10rows.csv"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");

        int recordCount = mysqlVerifier.countTargetRecords();
        assertThat(recordCount).isEqualTo(10);
    }

    @Test
    @Order(3)
    void shouldRunMultiFileJobInStandaloneMode() throws Exception {
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "multiFileEtlJob",
                Map.of("batch.multi-file.input-directory", "/data/test/multi"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");

        int recordCount = mysqlVerifier.countTargetRecords();
        assertThat(recordCount).isEqualTo(120);
    }
}
