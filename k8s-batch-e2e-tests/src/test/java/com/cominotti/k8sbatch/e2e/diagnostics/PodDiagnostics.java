// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.diagnostics;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Dumps pod status, logs, and events for test failure diagnostics.
 */
public final class PodDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(PodDiagnostics.class);
    private static final int MAX_LOG_LINES = 100;

    private final KubernetesClient client;
    private final String namespace;

    public PodDiagnostics(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    public void dumpAll() {
        dumpAllPodStatus();
        dumpPodLogs();
        dumpEvents();
    }

    public void dumpAllPodStatus() {
        log.error("=== POD STATUS DUMP ===");
        List<Pod> pods = client.pods().inNamespace(namespace).list().getItems();
        for (Pod pod : pods) {
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
            String conditions = "";
            if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
                conditions = pod.getStatus().getConditions().stream()
                        .map(c -> c.getType() + "=" + c.getStatus())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none");
            }
            log.error("Pod | name={} | phase={} | conditions=[{}]",
                    pod.getMetadata().getName(), phase, conditions);

            // Dump container statuses
            if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                pod.getStatus().getContainerStatuses().forEach(cs ->
                        log.error("  Container | name={} | ready={} | restarts={} | state={}",
                                cs.getName(), cs.getReady(), cs.getRestartCount(), cs.getState()));
            }
        }
    }

    public void dumpPodLogs() {
        log.error("=== POD LOGS DUMP ===");
        List<Pod> pods = client.pods().inNamespace(namespace).list().getItems();
        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();
            try {
                String logs = client.pods().inNamespace(namespace)
                        .withName(podName)
                        .tailingLines(MAX_LOG_LINES)
                        .getLog();
                log.error("--- Logs for pod {} (last {} lines) ---\n{}", podName, MAX_LOG_LINES, logs);
            } catch (Exception e) {
                log.error("Cannot get logs for pod {} : {}", podName, e.getMessage());
            }
        }
    }

    public void dumpEvents() {
        log.error("=== EVENTS DUMP ===");
        try {
            List<Event> events = client.v1().events().inNamespace(namespace).list().getItems();
            events.stream()
                    .sorted((a, b) -> {
                        String aTime = a.getLastTimestamp() != null ? a.getLastTimestamp() : "";
                        String bTime = b.getLastTimestamp() != null ? b.getLastTimestamp() : "";
                        return aTime.compareTo(bTime);
                    })
                    .forEach(event -> log.error("Event | type={} | reason={} | object={} | message={}",
                            event.getType(), event.getReason(),
                            event.getInvolvedObject() != null ? event.getInvolvedObject().getName() : "?",
                            event.getMessage()));
        } catch (Exception e) {
            log.error("Cannot dump events: {}", e.getMessage());
        }
    }
}
