// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it;

import com.cominotti.k8sbatch.gateway.GatewayApplication;
import com.cominotti.k8sbatch.gateway.it.config.WireMockBackendConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Base class for gateway integration tests.
 *
 * <p>Boots the gateway's own {@link GatewayApplication} context with WireMock as the backend stub.
 * Uses {@link RestClient} for HTTP calls (matching the project's Spring Boot 4.x convention).
 * The 30-second timeout is a backstop — gateway tests should complete in under 10 seconds.
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WireMockBackendConfig.class)
@ActiveProfiles("gateway-test")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public abstract class AbstractGatewayIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractGatewayIT.class);

    @LocalServerPort
    protected int port;

    @Autowired
    protected WireMockServer wireMock;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Injects the WireMock backend URL into the gateway's configuration. Uses
     * {@link DynamicPropertySource} instead of {@code System.setProperty} to guarantee the
     * property is available before Spring binds {@code GatewayProperties}.
     *
     * @param registry dynamic property registry
     */
    @DynamicPropertySource
    static void wireMockProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.backend-url", WireMockBackendConfig::backendUrl);
    }

    /**
     * Resets all WireMock stubs, request journal, and circuit breaker state between tests.
     * Without the circuit breaker reset, a test that intentionally trips the circuit (like
     * {@code CircuitBreakerIT}) would cause subsequent tests in the same Spring context to
     * receive 503 instead of the expected backend response.
     */
    @BeforeEach
    void resetTestState() {
        wireMock.resetAll();
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
        log.debug("Test state reset | gatewayPort={} | wireMockPort={}", port, wireMock.port());
    }

    /**
     * Creates a {@link RestClient} targeting the gateway's random port.
     *
     * @return pre-configured REST client for gateway requests
     */
    protected RestClient gatewayClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
    }
}
