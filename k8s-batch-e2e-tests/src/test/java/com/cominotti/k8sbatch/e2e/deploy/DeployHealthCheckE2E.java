// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.deploy;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.E2EProfile;
import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import com.cominotti.k8sbatch.e2e.cluster.PodUtils;
import io.fabric8.kubernetes.api.model.Pod;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Infrastructure smoke test that validates the Helm chart deploys correctly into a K3s cluster.
 * Checks that all pods (app, MySQL, Kafka) reach ready state, the Spring Boot actuator health
 * endpoint returns UP, and the MySQL target table is accessible. This test runs before any batch
 * job tests to catch deployment-level issues early.
 */
@E2EProfile("e2e-remote.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeployHealthCheckE2E extends AbstractE2ETest {

    /** {@inheritDoc} Deploys the full remote-partitioning stack (app, MySQL, Kafka, Schema Registry). */
    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    /** {@inheritDoc} Kafka is required for the full deployment health check. */
    @Override
    protected boolean requiresKafka() {
        return true;
    }

    /**
     * Verifies that every pod in the Helm release has reached the Ready condition.
     * This is the most basic deployment validation — if any pod is not ready, no
     * batch jobs can run.
     */
    @Test
    @Order(1)
    void allPodsShouldBeReady() {
        // Filter out Succeeded pods (e.g., completed Kafka topic-creation Job) —
        // they carry the same release label but are not long-running services.
        // A Succeeded pod never has Ready=True, so including it would always fail.
        List<Pod> pods = K3sClusterManager.client().pods()
                .inNamespace(K3sClusterManager.namespace())
                .withLabel("app.kubernetes.io/instance", K3sClusterManager.releaseName())
                .list().getItems().stream()
                .filter(pod -> !"Succeeded".equals(pod.getStatus().getPhase()))
                .toList();

        assertThat(pods).isNotEmpty();
        for (Pod pod : pods) {
            assertThat(PodUtils.isReady(pod))
                    .as("Pod %s should be Ready", pod.getMetadata().getName())
                    .isTrue();
        }
    }

    /**
     * Verifies that the Spring Boot actuator health endpoint returns UP, confirming
     * the application context started successfully and all health indicators (DB, Kafka)
     * are reporting healthy.
     */
    @Test
    @Order(2)
    void actuatorHealthShouldReturnUp() throws Exception {
        String health = appClient.getHealth();

        assertThat(health).contains("\"status\":\"UP\"");
    }

    /**
     * Verifies that the MySQL instance is accessible via JDBC port-forward and that the
     * {@code target_records} table exists (created by Flyway migrations) and is initially empty.
     */
    @Test
    @Order(3)
    void mysqlShouldBeAccessible() throws Exception {
        int count = mysqlVerifier.countTargetRecords();

        // Table exists and is empty initially
        assertThat(count).isEqualTo(0);
    }
}
