// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it.resilience;

import com.cominotti.k8sbatch.gateway.it.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Resilience4j circuit breaker trips after consecutive backend failures and
 * returns 503 (Service Unavailable) without forwarding requests to the backend.
 *
 * <p>The test profile configures a low failure threshold (5 calls minimum, 50% failure rate)
 * and a short wait duration (5 seconds) for fast test execution.
 */
class CircuitBreakerIT extends AbstractGatewayIT {

    @Test
    void shouldTripCircuitBreakerAfterConsecutiveFailures() {
        wireMock.stubFor(post(urlPathMatching("/api/jobs/.*"))
                .willReturn(serverError()));

        // Send enough requests to trip the circuit breaker.
        // Test profile: minimumNumberOfCalls=5, slidingWindowSize=10, failureRateThreshold=50%.
        // All calls return 500 (100% failure rate), so the circuit opens after the window fills.
        for (int i = 0; i < 15; i++) {
            sendJobRequest();
        }

        // Record how many requests WireMock has received so far
        int requestCountBefore = wireMock.countRequestsMatching(
                postRequestedFor(urlPathMatching("/api/jobs/.*")).build()).getCount();

        // Send another request — circuit should be open, so WireMock should NOT receive it
        int status = sendJobRequest();

        int requestCountAfter = wireMock.countRequestsMatching(
                postRequestedFor(urlPathMatching("/api/jobs/.*")).build()).getCount();

        // When circuit is open, gateway returns a server error (500 or 503) and does not
        // forward to backend — the request count should not increase
        assertThat(status).isIn(500, 503);
        assertThat(requestCountAfter).isEqualTo(requestCountBefore);
    }

    private int sendJobRequest() {
        try {
            return gatewayClient().post()
                    .uri("/api/jobs/testJob")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .onStatus(s -> s.isError(), (req, res) -> {})
                    .toEntity(String.class)
                    .getStatusCode()
                    .value();
        } catch (Exception e) {
            return 503;
        }
    }
}
