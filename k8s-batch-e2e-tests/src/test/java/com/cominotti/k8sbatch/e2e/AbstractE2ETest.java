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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@ExtendWith(E2EDiagnosticsExtension.class)
public abstract class AbstractE2ETest {

    private static final Logger log = LoggerFactory.getLogger(AbstractE2ETest.class);

    protected PortForwardManager portForwardManager;
    protected BatchAppClient appClient;
    protected MysqlVerifier mysqlVerifier;

    protected abstract String valuesFile();

    protected boolean requiresKafka() {
        return false;
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
        mysqlVerifier = new MysqlVerifier(mysqlPort, "k8sbatch", "k8sbatch", "e2e_pass");

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
        K3sClusterManager.loadImage("k8s-batch:e2e");
        K3sClusterManager.loadImage("mysql:8.0");
        K3sClusterManager.loadImage("busybox:1.36");

        if (requiresKafka()) {
            K3sClusterManager.loadImage("bitnami/kafka:3.7");
        }
    }
}
