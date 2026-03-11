// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.cluster;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages Fabric8 port forwards for accessing K8s services from the test JVM.
 */
public final class PortForwardManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(PortForwardManager.class);

    private final KubernetesClient client;
    private final String namespace;
    private final List<LocalPortForward> forwards = new ArrayList<>();

    public PortForwardManager(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /**
     * Port-forwards to a pod matching the given labels.
     *
     * @return the local port
     */
    public int forwardToService(Map<String, String> labels, int containerPort) {
        List<Pod> pods = client.pods().inNamespace(namespace)
                .withLabels(labels)
                .list().getItems();

        Pod readyPod = pods.stream()
                .filter(PodUtils::isReady)
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "No ready pod found for labels " + labels));

        String podName = readyPod.getMetadata().getName();
        LocalPortForward portForward = client.pods().inNamespace(namespace)
                .withName(podName)
                .portForward(containerPort);

        forwards.add(portForward);
        int localPort = portForward.getLocalPort();
        log.info("Port-forward established | pod={} | containerPort={} | localPort={}",
                podName, containerPort, localPort);
        return localPort;
    }

    /**
     * Port-forwards to the app pod (component=app).
     */
    public int forwardToApp(int containerPort) {
        return forwardToService(Map.of(
                "app.kubernetes.io/instance", K3sClusterManager.releaseName(),
                "app.kubernetes.io/component", "app"), containerPort);
    }

    /**
     * Port-forwards to the MySQL pod (component=mysql).
     */
    public int forwardToMysql(int containerPort) {
        return forwardToService(Map.of(
                "app.kubernetes.io/instance", K3sClusterManager.releaseName(),
                "app.kubernetes.io/component", "mysql"), containerPort);
    }

    @Override
    public void close() {
        for (LocalPortForward forward : forwards) {
            try {
                forward.close();
            } catch (Exception e) {
                log.debug("Error closing port forward: {}", e.getMessage());
            }
        }
        forwards.clear();
        log.debug("Closed all port forwards");
    }

}
