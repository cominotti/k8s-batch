// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Starts a WireMock server on a dynamic port to stub the {@code k8s-batch-jobs} backend.
 *
 * <p>Follows the {@code ContainerHolder} singleton pattern: one static server instance started once
 * in a static initializer, shared across all test classes in the JVM.
 *
 * <p>The backend URL is injected via {@code @DynamicPropertySource} in {@code AbstractGatewayIT},
 * not via {@code System.setProperty}, because Spring Cloud's {@code @RefreshScope} can interfere
 * with system property resolution timing.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WireMockBackendConfig {

    private static final Logger log = LoggerFactory.getLogger(WireMockBackendConfig.class);

    static final WireMockServer WIREMOCK = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        WIREMOCK.start();
        log.info("WireMock backend started | port={}", WIREMOCK.port());
    }

    /**
     * Returns the backend URL pointing to the running WireMock server.
     *
     * @return WireMock base URL
     */
    public static String backendUrl() {
        return "http://localhost:" + WIREMOCK.port();
    }

    /**
     * Exposes the WireMock server as a bean so tests can set up stubs and verify requests.
     *
     * @return the shared WireMock server
     */
    @Bean
    WireMockServer wireMockServer() {
        return WIREMOCK;
    }
}
