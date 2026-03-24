// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.rest;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the Prometheus metrics scrape endpoint is functional and returns expected metrics.
 *
 * <p>Confirms the full Micrometer auto-configuration chain is active: {@code ObservationRegistry}
 * {@literal →} {@code DefaultMeterObservationHandler} {@literal →} {@code PrometheusMeterRegistry}
 * {@literal →} {@code /actuator/prometheus} endpoint. JVM metrics are always present; Spring Batch
 * metrics ({@code spring_batch_*}) only appear after a job executes.
 */
class ActuatorPrometheusIT extends AbstractIntegrationTest {

    private String getPrometheusMetrics() {
        return restClient().get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);
    }

    @Test
    void shouldContainJvmMemoryMetrics() {
        String metrics = getPrometheusMetrics();
        assertThat(metrics).contains("jvm_memory");
    }

    @Test
    void shouldContainHikariConnectionPoolMetrics() {
        String metrics = getPrometheusMetrics();
        assertThat(metrics).contains("hikaricp_connections");
    }

    @Test
    void shouldContainApplicationCommonTag() {
        String metrics = getPrometheusMetrics();
        assertThat(metrics).contains("application=\"k8s-batch\"");
    }
}
