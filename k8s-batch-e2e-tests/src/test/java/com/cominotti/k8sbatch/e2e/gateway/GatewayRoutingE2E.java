// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.gateway;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.client.BatchAppClient.JobResponse;
import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import com.cominotti.k8sbatch.e2e.cluster.PodUtils;
import io.fabric8.kubernetes.api.model.Pod;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test that verifies the API gateway routes requests correctly to the backend
 * application through a full K3s deployment. The gateway pod runs alongside the app,
 * MySQL, and Kafka pods, proxying HTTP traffic via Spring Cloud Gateway routes.
 * Tests are ordered: infrastructure checks first, then functional routing tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayRoutingE2E extends AbstractE2ETest {

    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    @Override
    protected boolean requiresKafka() {
        return true;
    }

    @Override
    protected boolean requiresGateway() {
        return true;
    }

    @Test
    @Order(1)
    void gatewayPodShouldBeReady() {
        List<Pod> pods = K3sClusterManager.client().pods()
                .inNamespace(K3sClusterManager.namespace())
                .withLabel("app.kubernetes.io/instance", K3sClusterManager.releaseName())
                .withLabel("app.kubernetes.io/component", "gateway")
                .list().getItems();

        assertThat(pods).hasSize(1);
        assertThat(PodUtils.isReady(pods.get(0)))
                .as("Gateway pod %s should be Ready", pods.get(0).getMetadata().getName())
                .isTrue();
    }

    @Test
    @Order(2)
    void gatewayHealthShouldReturnUp() throws Exception {
        String health = gatewayClient.getHealth();

        assertThat(health).contains("\"status\":\"UP\"");
    }

    @Test
    @Order(3)
    void helloThroughGatewayShouldReturnMessage() throws Exception {
        String response = gatewayClient.getHello();

        assertThat(response).contains("Hello from k8s-batch!");
    }

    @Test
    @Order(4)
    void jobLaunchThroughGatewayShouldComplete() throws Exception {
        JobResponse result = gatewayClient.launchJobAndWaitForCompletion(
                "fileRangeEtlJob",
                Map.of("batch.file-range.input-file", "/data/test/sample-10rows.csv"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.exitCode()).isEqualTo("COMPLETED");

        // Verify data was written to MySQL (through the backend, triggered via gateway)
        int recordCount = mysqlVerifier.countTargetRecords();
        assertThat(recordCount).isEqualTo(10);
    }
}
