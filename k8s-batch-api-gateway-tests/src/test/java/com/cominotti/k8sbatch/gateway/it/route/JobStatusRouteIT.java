// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it.route;

import com.cominotti.k8sbatch.gateway.it.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code GET /api/jobs/{jobName}/executions/{executionId}} is routed to the backend
 * with path variables preserved.
 */
class JobStatusRouteIT extends AbstractGatewayIT {

    @Test
    void shouldForwardExecutionStatusPollToBackend() {
        String statusResponse = """
                {"executionId":42,"jobName":"fileRangeEtlJob","status":"COMPLETED","exitCode":"COMPLETED","exitDescription":""}""";
        wireMock.stubFor(get(urlEqualTo("/api/jobs/fileRangeEtlJob/executions/42"))
                .willReturn(okJson(statusResponse)));

        String body = gatewayClient().get()
                .uri("/api/jobs/fileRangeEtlJob/executions/42")
                .retrieve()
                .body(String.class);

        assertThat(body).contains("COMPLETED");
        assertThat(body).contains("42");
        wireMock.verify(1, getRequestedFor(urlEqualTo("/api/jobs/fileRangeEtlJob/executions/42")));
    }

    @Test
    void shouldPassThrough404ForUnknownExecution() {
        wireMock.stubFor(get(urlEqualTo("/api/jobs/fileRangeEtlJob/executions/999"))
                .willReturn(aResponse().withStatus(404)));

        ResponseEntity<String> response = gatewayClient().get()
                .uri("/api/jobs/fileRangeEtlJob/executions/999")
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), (req, res) -> {})
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
