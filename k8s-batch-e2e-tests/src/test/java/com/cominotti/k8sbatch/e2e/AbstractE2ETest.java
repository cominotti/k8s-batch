// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e;

import com.cominotti.k8sbatch.e2e.client.BatchAppClient;
import com.cominotti.k8sbatch.e2e.client.MysqlVerifier;
import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import com.cominotti.k8sbatch.e2e.cluster.PortForwardManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Base class for E2E tests that deploy the Helm chart into a K3s cluster and verify behavior
 * through port-forwarded HTTP and JDBC connections.
 *
 * <p>{@code @TestInstance(PER_CLASS)} prevents re-running Helm deploy per test method — setup
 * happens once in {@code @BeforeAll}. Subclasses specify which Helm values file to deploy via
 * {@link #valuesFile()}, which determines the infrastructure (Kafka vs standalone). Override
 * {@link #requiresKafka()} to load Kafka/Schema Registry images (skipped by default since they
 * are large and slow). Test data is cleaned before each test (not after) so that failing tests
 * leave data visible for post-mortem analysis.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@ExtendWith(E2EDiagnosticsExtension.class)
public abstract class AbstractE2ETest {

    private static final Logger log = LoggerFactory.getLogger(AbstractE2ETest.class);

    protected PortForwardManager portForwardManager;
    protected BatchAppClient appClient;
    protected BatchAppClient gatewayClient;
    protected MysqlVerifier mysqlVerifier;

    protected abstract String valuesFile();

    protected boolean requiresKafka() {
        return false;
    }

    /**
     * Whether to load the gateway Docker image into K3s. Defaults to {@link #requiresKafka()}
     * because the gateway is always co-deployed in the remote profile ({@code e2e-remote.yaml}).
     * Standalone tests skip both Kafka and gateway.
     */
    protected boolean requiresGateway() {
        return requiresKafka();
    }

    @BeforeAll
    void setUp() throws Exception {
        log.info("Setting up E2E test | class={} | valuesFile={}", getClass().getSimpleName(), valuesFile());

        K3sClusterManager.ensureClusterRunning();
        loadRequiredImages();
        K3sClusterManager.deploy(valuesFile());

        portForwardManager = new PortForwardManager(
                K3sClusterManager.client(), K3sClusterManager.namespace());

        int appPort = portForwardManager.forwardToApp(8080);
        int mysqlPort = portForwardManager.forwardToMysql(3306);

        appClient = new BatchAppClient(appPort);
        // Credentials must match the Helm values file (mysql.auth.database/username/password)
        mysqlVerifier = new MysqlVerifier(mysqlPort, "k8sbatch", "k8sbatch", "e2e_pass");

        if (requiresGateway()) {
            int gatewayPort = portForwardManager.forwardToGateway(9090);
            gatewayClient = new BatchAppClient(gatewayPort);
            log.info("Gateway port-forward established | gatewayPort={}", gatewayPort);
        }

        log.info("E2E test setup complete | appPort={} | mysqlPort={}", appPort, mysqlPort);
    }

    @AfterAll
    void tearDown() {
        if (portForwardManager != null) {
            portForwardManager.close();
        }
        log.info("E2E test teardown complete | class={}", getClass().getSimpleName());
    }

    @BeforeEach
    void cleanTestData() throws Exception {
        if (mysqlVerifier != null) {
            mysqlVerifier.cleanTargetRecords();
        }
    }

    private void loadRequiredImages() throws Exception {
        K3sClusterManager.loadImage(E2EContainerImages.APP_IMAGE);
        K3sClusterManager.loadImage(E2EContainerImages.MYSQL_IMAGE);
        K3sClusterManager.loadImage(E2EContainerImages.BUSYBOX_IMAGE);

        if (requiresKafka()) {
            K3sClusterManager.loadImage(E2EContainerImages.KAFKA_IMAGE);
            K3sClusterManager.loadImage(E2EContainerImages.SCHEMA_REGISTRY_IMAGE);
        }

        if (requiresGateway()) {
            K3sClusterManager.loadImage(E2EContainerImages.GATEWAY_IMAGE);
        }
    }
}
