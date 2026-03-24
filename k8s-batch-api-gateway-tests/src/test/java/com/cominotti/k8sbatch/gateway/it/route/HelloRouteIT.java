// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it.route;

import com.cominotti.k8sbatch.gateway.it.AbstractGatewayIT;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code GET /hello} is routed to the backend without rate limiting or circuit
 * breaking.
 */
class HelloRouteIT extends AbstractGatewayIT {

    @Test
    void shouldForwardGetHelloToBackend() {
        wireMock.stubFor(get(urlEqualTo("/hello"))
                .willReturn(okJson("{\"message\":\"Hello from k8s-batch!\"}")));

        String body = gatewayClient().get()
                .uri("/hello")
                .retrieve()
                .body(String.class);

        assertThat(body).contains("Hello from k8s-batch!");
        wireMock.verify(1, getRequestedFor(urlEqualTo("/hello")));
    }

    @Test
    void shouldPreserveAcceptHeaderOnHelloRoute() {
        wireMock.stubFor(get(urlEqualTo("/hello"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(okJson("{\"message\":\"Hello from k8s-batch!\"}")));

        gatewayClient().get()
                .uri("/hello")
                .header("Accept", "application/json")
                .retrieve()
                .body(String.class);

        wireMock.verify(1, getRequestedFor(urlEqualTo("/hello"))
                .withHeader("Accept", equalTo("application/json")));
    }
}
