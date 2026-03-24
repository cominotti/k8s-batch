// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it;

import com.cominotti.k8sbatch.K8sBatchApplication;
import com.cominotti.k8sbatch.it.config.SharedContainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Base class for integration tests that need the full remote-partitioning stack with a web server.
 *
 * <p>Starts a Spring Boot context with MySQL + Redpanda containers, both the
 * {@code integration-test} and {@code remote-partitioning} profiles active, and a web server on a
 * random port. Uses {@link RestClient} (replaces {@code TestRestTemplate}, which was removed in
 * Spring Boot 4.x). The 120-second timeout is a backstop for remote partitioning operations.
 */
@SpringBootTest(classes = K8sBatchApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SharedContainersConfig.class)
@ActiveProfiles({"integration-test", "remote-partitioning", "remote-kafka"})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public abstract class AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractIntegrationTest.class);

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void logTestPort() {
        log.debug("Integration test server running on port {}", port);
    }

    protected RestClient restClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
    }
}
