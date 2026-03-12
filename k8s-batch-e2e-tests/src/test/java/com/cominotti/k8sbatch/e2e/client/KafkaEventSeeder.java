// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.client;

import com.cominotti.k8sbatch.avro.TransactionEvent;
import com.cominotti.k8sbatch.e2e.E2EContainerImages;
import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Seeds Avro-serialized {@code TransactionEvent} messages into a Kafka topic inside K3s by
 * creating a Kubernetes Job that runs {@code kafka-avro-console-producer} from the Schema
 * Registry image (already loaded into K3s).
 *
 * <p>This avoids the Kafka advertised-listener DNS resolution issue that occurs when producing
 * from outside K3s via port-forwarding — all Kafka interaction happens inside the cluster.
 */
public final class KafkaEventSeeder {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSeeder.class);
    private static final String SEEDER_CONFIGMAP = "transaction-events-seed";
    private static final String SEEDER_JOB = "transaction-event-seeder";

    private KafkaEventSeeder() {
    }

    /**
     * Produces transaction events to the given topic inside K3s.
     *
     * @param client     Fabric8 Kubernetes client
     * @param namespace  target namespace
     * @param topic      Kafka topic to produce to
     * @param eventsJson list of JSON-encoded TransactionEvent objects (one per entry)
     */
    public static void seedEvents(KubernetesClient client, String namespace,
                                  String topic, List<String> eventsJson) throws Exception {
        log.info("Seeding {} events to topic '{}' via K8s Job", eventsJson.size(), topic);

        String kafkaBootstrap = K3sClusterManager.releaseName() + "-k8s-batch-kafka:9092";
        String schemaRegistryUrl = "http://" + K3sClusterManager.releaseName()
                + "-k8s-batch-schema-registry:8081";

        // Create ConfigMap with the events data (one JSON per line)
        String eventsData = String.join("\n", eventsJson) + "\n";
        cleanupPrevious(client, namespace);

        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(SEEDER_CONFIGMAP)
                    .withNamespace(namespace)
                .endMetadata()
                .withData(Map.of("events.json", eventsData))
                .build();
        client.configMaps().inNamespace(namespace).resource(configMap).unlock().createOr(NonDeletingOperation::update);
        log.debug("Created seeder ConfigMap | events={}", eventsJson.size());

        // Derive schema from the generated Avro class to stay in sync with TransactionEvent.avsc
        String avroSchema = TransactionEvent.getClassSchema().toString();

        // Create a K8s Job that pipes the events through kafka-avro-console-producer
        Job seederJob = new JobBuilder()
                .withNewMetadata()
                    .withName(SEEDER_JOB)
                    .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(3)
                    .withNewTemplate()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewContainer()
                                .withName("producer")
                                .withImage(E2EContainerImages.SCHEMA_REGISTRY_IMAGE)
                                .withCommand("bash", "-c",
                                        "cat /data/events.json | kafka-avro-console-producer "
                                        + "--broker-list " + kafkaBootstrap + " "
                                        + "--topic " + topic + " "
                                        + "--property schema.registry.url=" + schemaRegistryUrl + " "
                                        + "--property value.schema='" + avroSchema + "'")
                                .addNewVolumeMount()
                                    .withName("events")
                                    .withMountPath("/data")
                                    .withReadOnly(true)
                                .endVolumeMount()
                            .endContainer()
                            .addNewVolume()
                                .withName("events")
                                .withNewConfigMap()
                                    .withName(SEEDER_CONFIGMAP)
                                .endConfigMap()
                            .endVolume()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        client.batch().v1().jobs().inNamespace(namespace).resource(seederJob).unlock().createOr(NonDeletingOperation::update);
        log.info("Created seeder Job | image={}", E2EContainerImages.SCHEMA_REGISTRY_IMAGE);

        // Wait for the job to complete
        client.batch().v1().jobs().inNamespace(namespace).withName(SEEDER_JOB)
                .waitUntilCondition(
                        j -> j != null && j.getStatus() != null
                                && (j.getStatus().getSucceeded() != null && j.getStatus().getSucceeded() > 0
                                    || j.getStatus().getFailed() != null && j.getStatus().getFailed() > 0),
                        120, TimeUnit.SECONDS);

        Job completedJob = client.batch().v1().jobs().inNamespace(namespace)
                .withName(SEEDER_JOB).get();
        if (completedJob.getStatus().getSucceeded() == null
                || completedJob.getStatus().getSucceeded() == 0) {
            String logs = client.batch().v1().jobs().inNamespace(namespace)
                    .withName(SEEDER_JOB).getLog();
            throw new RuntimeException("Kafka seeder job failed. Logs:\n" + logs);
        }
        log.info("Seeder Job completed successfully | events={}", eventsJson.size());
    }

    private static void cleanupPrevious(KubernetesClient client, String namespace) {
        try {
            client.batch().v1().jobs().inNamespace(namespace).withName(SEEDER_JOB)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            log.debug("No previous seeder Job to clean up (expected on first run)");
        }
        try {
            client.configMaps().inNamespace(namespace).withName(SEEDER_CONFIGMAP).delete();
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            log.debug("No previous seeder ConfigMap to clean up (expected on first run)");
        }
    }
}
