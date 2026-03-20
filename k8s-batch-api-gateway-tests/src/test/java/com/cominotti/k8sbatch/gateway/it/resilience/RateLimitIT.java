// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it.resilience;

import com.cominotti.k8sbatch.gateway.it.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Bucket4j rate limiter enforces capacity limits on the job API routes.
 *
 * <p>{@link TestPropertySource} overrides the rate limit capacity to 10 requests, which forces
 * Spring to create a separate context with a fresh Bucket4j bucket. This avoids sharing the
 * in-memory rate limit state with other test classes that use the default high capacity.
 */
@TestPropertySource(properties = "gateway.rate-limit.capacity=10")
class RateLimitIT extends AbstractGatewayIT {

    @Test
    void shouldReturnTooManyRequestsWhenRateLimitExceeded() {
        wireMock.stubFor(post(urlPathMatching("/api/jobs/.*"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"executionId\":1,\"jobName\":\"test\",\"status\":\"STARTING\"}")));

        // Send more requests than the rate limit allows (capacity=10 via @TestPropertySource)
        List<Integer> statusCodes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            statusCodes.add(sendJobRequest());
        }

        // At least one request should be rate-limited (429 Too Many Requests)
        assertThat(statusCodes).contains(429);
    }

    @Test
    void shouldNotRateLimitHelloEndpoint() {
        wireMock.stubFor(get(urlPathMatching("/hello"))
                .willReturn(okJson("{\"message\":\"Hello from k8s-batch!\"}")));

        // Send many requests to /hello — none should be rate-limited
        List<Integer> statusCodes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            statusCodes.add(sendGetRequest("/hello"));
        }

        assertThat(statusCodes).allMatch(code -> code == 200);
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
            return -1;
        }
    }

    private int sendGetRequest(String uri) {
        try {
            return gatewayClient().get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(s -> s.isError(), (req, res) -> {})
                    .toEntity(String.class)
                    .getStatusCode()
                    .value();
        } catch (Exception e) {
            return -1;
        }
    }
}
