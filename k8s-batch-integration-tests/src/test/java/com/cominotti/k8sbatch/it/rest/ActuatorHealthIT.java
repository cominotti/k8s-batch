package com.cominotti.k8sbatch.it.rest;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(components).containsKey("db");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldShowKafkaHealthIndicator() {
        Map<String, Object> health = getHealth();
        Map<String, Object> components = (Map<String, Object>) health.get("components");
        assertThat(components).containsKey("kafka");
    }

    @Test
    void shouldExposeHealthDetails() {
        Map<String, Object> health = getHealth();
        assertThat(health).containsKey("components");
        assertThat(health.get("components")).isNotNull();
    }
}
