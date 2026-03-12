// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.deploy;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeployHealthCheckE2E extends AbstractE2ETest {

    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    @Override
    protected boolean requiresKafka() {
        return true;
    }

    @Test
    @Order(1)
    void allPodsShouldBeReady() {
        List<Pod> pods = K3sClusterManager.client().pods()
                .inNamespace(K3sClusterManager.namespace())
                .withLabel("app.kubernetes.io/instance", K3sClusterManager.releaseName())
                .list().getItems();

        assertThat(pods).isNotEmpty();
        for (Pod pod : pods) {
            assertThat(PodUtils.isReady(pod))
                    .as("Pod %s should be Ready", pod.getMetadata().getName())
                    .isTrue();
        }
    }

    @Test
    @Order(2)
    void actuatorHealthShouldReturnUp() throws Exception {
        String health = appClient.getHealth();

        assertThat(health).contains("\"status\":\"UP\"");
    }

    @Test
    @Order(3)
    void mysqlShouldBeAccessible() throws Exception {
        int count = mysqlVerifier.countTargetRecords();

        // Table exists and is empty initially
        assertThat(count).isEqualTo(0);
    }
}
