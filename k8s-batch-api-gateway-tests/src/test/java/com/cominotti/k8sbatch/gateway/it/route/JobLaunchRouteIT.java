// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it.route;

import com.cominotti.k8sbatch.gateway.it.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code POST /api/jobs/{jobName}} is routed to the backend with the request body
 * forwarded intact and the 202 status preserved.
 */
class JobLaunchRouteIT extends AbstractGatewayIT {

    private static final String LAUNCH_RESPONSE = """
            {"executionId":1,"jobName":"fileRangeEtlJob","status":"STARTING","exitCode":"UNKNOWN","exitDescription":""}""";

    @Test
    void shouldForwardJobLaunchAndPreserve202Status() {
        wireMock.stubFor(post(urlEqualTo("/api/jobs/fileRangeEtlJob"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(LAUNCH_RESPONSE)));

        ResponseEntity<String> response = gatewayClient().post()
                .uri("/api/jobs/fileRangeEtlJob")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("inputFile", "/data/test.csv"))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).contains("fileRangeEtlJob");
    }

    @Test
    void shouldForwardRequestBodyToBackend() {
        wireMock.stubFor(post(urlEqualTo("/api/jobs/multiFileEtlJob"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(LAUNCH_RESPONSE)));

        gatewayClient().post()
                .uri("/api/jobs/multiFileEtlJob")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("inputDir", "/data/batch/"))
                .retrieve()
                .toEntity(String.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/jobs/multiFileEtlJob"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("inputDir")));
    }

    @Test
    void shouldPassThrough400ForUnknownJob() {
        String errorBody = """
                {"executionId":-1,"jobName":"nonExistent","status":"FAILED","exitCode":"FAILED","exitDescription":"Unknown job"}""";
        wireMock.stubFor(post(urlEqualTo("/api/jobs/nonExistent"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody(errorBody)));

        ResponseEntity<String> response = gatewayClient().post()
                .uri("/api/jobs/nonExistent")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .onStatus(status -> status.is4xxClientError(), (req, res) -> {})
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
