package com.cominotti.k8sbatch.it;

import com.cominotti.k8sbatch.K8sBatchApplication;
import com.cominotti.k8sbatch.it.config.BatchTestJobConfig;
import com.cominotti.k8sbatch.it.config.MysqlOnlyContainersConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = K8sBatchApplication.class)
@Import({MysqlOnlyContainersConfig.class, BatchTestJobConfig.class})
@ActiveProfiles({"integration-test", "standalone"})
public abstract class AbstractStandaloneBatchTest extends AbstractBatchIntegrationTest {
}
