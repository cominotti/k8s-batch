package com.cominotti.k8sbatch.it.rest;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class HelloEndpointIT extends AbstractIntegrationTest {

    @Test
    void shouldReturnHelloMessage() {
        String body = restClient().get()
                .uri("/hello")
                .retrieve()
                .body(String.class);

        assertThat(body).contains("Hello from k8s-batch!");
    }

    @Test
    void shouldReturnJsonContentType() {
        String body = restClient().get()
                .uri("/hello")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotNull();
        assertThat(body).startsWith("{");
    }
}
