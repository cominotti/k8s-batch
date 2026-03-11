package com.cominotti.k8sbatch.it;

import com.cominotti.k8sbatch.K8sBatchApplication;
import com.cominotti.k8sbatch.it.config.BatchTestJobConfig;
import com.cominotti.k8sbatch.it.config.MysqlOnlyContainersConfig;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

@SpringBootTest(classes = K8sBatchApplication.class)
@Import({MysqlOnlyContainersConfig.class, BatchTestJobConfig.class})
@ActiveProfiles({"integration-test", "standalone"})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public abstract class AbstractStandaloneBatchTest extends AbstractBatchIntegrationTest {
}
