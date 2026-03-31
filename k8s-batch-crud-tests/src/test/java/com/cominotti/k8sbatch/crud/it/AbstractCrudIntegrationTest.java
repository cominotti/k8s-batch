// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it;

import com.cominotti.k8sbatch.crud.CrudApplication;
import com.cominotti.k8sbatch.crud.it.config.CrudMysqlContainersConfig;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Base class for full-context integration tests ({@code @SpringBootTest} with web server).
 *
 * <p>Provides {@link #restClient()} for REST API testing and automatic cleanup of test data
 * in FK-safe order after each test.
 */
@SpringBootTest(classes = CrudApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CrudMysqlContainersConfig.class)
@ActiveProfiles("crud-test")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public abstract class AbstractCrudIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractCrudIntegrationTest.class);
    private static final String ACCOUNTS_TABLE = "accounts";
    private static final String CUSTOMERS_TABLE = "customers";

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private RestClient cachedRestClient;

    /**
     * Ensures clean state before each test (handles stale data from failed prior cleanups).
     */
    @BeforeEach
    void ensureCleanState() {
        doCleanup();
    }

    /**
     * Cleans up test data in FK-safe order (accounts before customers).
     */
    @AfterEach
    void cleanupTestData() {
        doCleanup();
    }

    private void doCleanup() {
        // Both DELETEs must run in a single transaction because HikariCP auto-commit=false means
        // separate execute() calls get separate connections with uncommitted transactions — the
        // second DELETE can't see the first's changes, causing FK constraint violations.
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("DELETE FROM " + ACCOUNTS_TABLE);
            jdbcTemplate.execute("DELETE FROM " + CUSTOMERS_TABLE);
        });
    }

    /**
     * Creates a {@link RestClient} pointing to the random test server port.
     *
     * @return configured REST client
     */
    protected RestClient restClient() {
        if (cachedRestClient == null) {
            cachedRestClient = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .build();
        }
        return cachedRestClient;
    }
}
