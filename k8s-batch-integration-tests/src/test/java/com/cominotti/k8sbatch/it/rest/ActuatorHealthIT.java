// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.rest;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates Spring Boot Actuator health endpoint reports UP with expected components. */
class ActuatorHealthIT extends AbstractIntegrationTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> getHealth() {
        return restClient().get()
                .uri("/actuator/health")
                .retrieve()
                .body(Map.class);
    }

    @Test
    void shouldReturnStatusUp() {
        Map<String, Object> health = getHealth();
        assertThat(health).containsEntry("status", "UP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldShowMysqlHealthIndicator() {
        Map<String, Object> health = getHealth();
        assertThat(health).containsKey("components");
        Map<String, Object> components = (Map<String, Object>) health.get("components");
        // "db" is the Spring Boot auto-configured DataSourceHealthIndicator
        // Note: no Kafka health indicator exists in Spring Boot 4.0.3
        assertThat(components).containsKey("db");
    }

    @Test
    void shouldExposeHealthDetails() {
        Map<String, Object> health = getHealth();
        assertThat(health).containsKey("components");
        assertThat(health.get("components")).isNotNull();
    }
}
