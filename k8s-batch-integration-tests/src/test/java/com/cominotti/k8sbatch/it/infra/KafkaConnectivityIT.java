// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.infra;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConnectivityIT extends AbstractIntegrationTest {

    @Value("${spring.kafka.bootstrap-servers:}")
    private String bootstrapServers;

    @Test
    void shouldConnectToKafka() throws ExecutionException, InterruptedException, TimeoutException {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            String clusterId = admin.describeCluster().clusterId().get(30, TimeUnit.SECONDS);
            assertThat(clusterId).isNotBlank();
        }
    }

    @Test
    void shouldCreateTopic() throws ExecutionException, InterruptedException, TimeoutException {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(Collections.singletonList(
                    new NewTopic("test-connectivity-topic", 1, (short) 1))).all().get(30, TimeUnit.SECONDS);

            Set<String> topics = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(topics).contains("test-connectivity-topic");
        }
    }

    @Test
    void shouldListTopics() throws ExecutionException, InterruptedException, TimeoutException {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            Set<String> topics = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(topics).isNotNull();
        }
    }
}
