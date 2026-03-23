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

import java.util.ArrayList;
import java.util.List;
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
    protected MysqlVerifier mysqlVerifier;

    /**
     * Returns the Helm values filename to deploy for this test class.
     *
     * <p>Subclasses return either {@code "e2e-remote.yaml"} (Kafka-based remote partitioning)
     * or {@code "e2e-standalone.yaml"} (in-process partitioning without Kafka). The file is
     * resolved from the classpath {@code helm-values/} directory by
     * {@link K3sClusterManager#deploy(String)}.
     *
     * @return the values filename (e.g., {@code "e2e-remote.yaml"})
     */
    protected abstract String valuesFile();

    /**
     * Override hook that controls whether Kafka and Schema Registry images are loaded into K3s.
     *
     * <p>Returns {@code false} by default — subclasses that deploy the remote-partitioning
     * profile should override to return {@code true}. Loading these images adds ~45 seconds
     * of {@code docker save → ctr import} time, so they are skipped for standalone tests.
     *
     * @return {@code true} to load Kafka and Schema Registry images
     */
    protected boolean requiresKafka() {
        return false;
    }

    /**
     * One-time setup for all test methods in this class ({@code @TestInstance(PER_CLASS)}).
     *
     * <p>Execution order:
     * <ol>
     *   <li>Ensure the K3s cluster is running (singleton — first call starts it)</li>
     *   <li>Load required Docker images into K3s (parallel/batch via {@link K3sClusterManager#loadImages})</li>
     *   <li>Deploy the Helm chart with the profile-specific values file</li>
     *   <li>Port-forward to the app (8080) and MySQL (3306) pods</li>
     *   <li>Create HTTP and JDBC clients for test verification</li>
     * </ol>
     *
     * @throws Exception if cluster startup, image loading, deployment, or port-forwarding fails
     */
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

        log.info("E2E test setup complete | appPort={} | mysqlPort={}", appPort, mysqlPort);
    }

    /**
     * Closes port forwards after all test methods in this class complete.
     *
     * <p>The K3s cluster and Helm deployment are intentionally <em>not</em> torn down here —
     * they are reused by the next test class if it deploys the same profile. Profile switches
     * are handled by {@link K3sClusterManager#deploy(String)} which tears down and redeploys
     * when the values file changes.
     */
    @AfterAll
    void tearDown() {
        if (portForwardManager != null) {
            portForwardManager.close();
        }
        log.info("E2E test teardown complete | class={}", getClass().getSimpleName());
    }

    /**
     * Cleans MySQL test data <em>before</em> each test method (not after).
     *
     * <p>This ordering is deliberate: when a test fails, the data it wrote remains in MySQL
     * for post-mortem analysis. Cleaning before the next test ensures a fresh starting state
     * without hiding evidence of the previous failure.
     *
     * @throws Exception if the cleanup query fails
     */
    @BeforeEach
    void cleanTestData() throws Exception {
        if (mysqlVerifier != null) {
            mysqlVerifier.cleanTargetRecords();
        }
    }

    /**
     * Returns the list of Docker images required by this test class.
     * Override to customize for tests that need fewer (or additional) images.
     *
     * @return list of image names from {@link E2EContainerImages}
     */
    protected List<String> requiredImages() {
        List<String> images = new ArrayList<>();
        images.add(E2EContainerImages.APP_IMAGE);
        images.add(E2EContainerImages.MYSQL_IMAGE);
        images.add(E2EContainerImages.BUSYBOX_IMAGE);

        if (requiresKafka()) {
            images.add(E2EContainerImages.KAFKA_IMAGE);
            images.add(E2EContainerImages.SCHEMA_REGISTRY_IMAGE);
        }
        return images;
    }

    /**
     * Loads all Docker images returned by {@link #requiredImages()} into the K3s cluster.
     *
     * <p>Delegates to {@link K3sClusterManager#loadImages(List)} which uses
     * {@link com.cominotti.k8sbatch.e2e.cluster.K3sImageLoader} for parallel or batch loading.
     * Images already loaded (from a previous test class in the same JVM) are skipped.
     *
     * @throws Exception if any image fails to save, copy, or import
     */
    private void loadRequiredImages() throws Exception {
        K3sClusterManager.loadImages(requiredImages());
    }
}
