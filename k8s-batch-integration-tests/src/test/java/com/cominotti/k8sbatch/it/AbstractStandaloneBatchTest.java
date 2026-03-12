// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it;

import com.cominotti.k8sbatch.K8sBatchApplication;
import com.cominotti.k8sbatch.it.config.BatchTestJobConfig;
import com.cominotti.k8sbatch.it.config.MysqlOnlyContainersConfig;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

/**
 * Base class for standalone-mode batch tests using in-process {@code TaskExecutorPartitionHandler}
 * (no Kafka). Uses {@link MysqlOnlyContainersConfig} to skip Redpanda startup entirely.
 *
 * <p>The 30-second timeout (vs 120s for remote tests) reflects that standalone partitioning is
 * synchronous and much faster than Kafka-based remote partitioning.
 */
@SpringBootTest(classes = K8sBatchApplication.class)
@Import({MysqlOnlyContainersConfig.class, BatchTestJobConfig.class})
@ActiveProfiles({"integration-test", "standalone"})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public abstract class AbstractStandaloneBatchTest extends AbstractBatchIntegrationTest {
}
