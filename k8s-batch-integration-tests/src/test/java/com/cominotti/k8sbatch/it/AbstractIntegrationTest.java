package com.cominotti.k8sbatch.it;

import com.cominotti.k8sbatch.K8sBatchApplication;
import com.cominotti.k8sbatch.it.config.SharedContainersConfig;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

@SpringBootTest(classes = K8sBatchApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SharedContainersConfig.class)
@ActiveProfiles({"integration-test", "remote-partitioning"})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected RestClient restClient() {
        return RestClient.create("http://localhost:" + port);
    }
}
