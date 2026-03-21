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

    /**
     * Creates a diagnostics dumper for the given namespace. Used by both
     * {@link com.cominotti.k8sbatch.e2e.E2EDiagnosticsExtension} (on test failure) and
     * {@link com.cominotti.k8sbatch.e2e.cluster.DeploymentWaiter} (on deployment failure) to
     * produce detailed failure output.
     *
     * @param client    the Fabric8 Kubernetes client connected to the K3s cluster
     * @param namespace the namespace containing the pods to diagnose
     */
    public PodDiagnostics(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /**
     * Dumps diagnostics for all pods in the namespace.
     */
    public void dumpAll() {
        dumpForPods(allPods());
    }

    /**
     * Dumps diagnostics for the specified pods (status, logs, events).
     */
    public void dumpForPods(List<Pod> pods) {
        dumpPodStatus(pods);
        dumpPodLogs(pods);
        dumpEvents();
    }

    /**
     * Dumps all Kubernetes events in the namespace, sorted by lastTimestamp for chronological
     * replay. Events include scheduling decisions, image pulls, container state transitions,
     * and probe failures — essential for diagnosing why a pod failed to become ready.
     */
    private void dumpEvents() {
        log.error("=== EVENTS DUMP ===");
        try {
            List<Event> events = client.v1().events().inNamespace(namespace).list().getItems();
            // Sort by lastTimestamp for chronological replay — helps trace the sequence of
            // scheduling, pulling, and startup events
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

    /**
     * Lists all pods in the namespace (unfiltered by labels). Used by {@link #dumpAll()} to
     * capture the complete pod state including system pods that might affect test pods.
     *
     * @return all pods in the namespace
     */
    private List<Pod> allPods() {
        return client.pods().inNamespace(namespace).list().getItems();
    }

    /**
     * Logs the phase, conditions, and container statuses for each pod. Init container statuses
     * are logged separately from main container statuses because they run sequentially — seeing
     * which init container is stuck reveals exactly where the startup pipeline stalled (e.g.,
     * "wait-for-kafka" init container still running means Kafka hasn't started yet).
     *
     * @param pods the pods to dump status for
     */
    private void dumpPodStatus(List<Pod> pods) {
        log.error("=== POD STATUS DUMP ===");
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

            // Init containers run sequentially — logging them separately reveals which step
            // in the startup chain stalled
            if (pod.getStatus() != null && pod.getStatus().getInitContainerStatuses() != null) {
                pod.getStatus().getInitContainerStatuses().forEach(cs ->
                        log.error("  InitContainer | name={} | ready={} | restarts={} | state={}",
                                cs.getName(), cs.getReady(), cs.getRestartCount(), cs.getState()));
            }
            if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                pod.getStatus().getContainerStatuses().forEach(cs ->
                        log.error("  Container | name={} | ready={} | restarts={} | state={}",
                                cs.getName(), cs.getReady(), cs.getRestartCount(), cs.getState()));
            }
        }
    }

    /**
     * Tails the last {@value #MAX_LOG_LINES} lines from each pod's main container. Catches and
     * logs exceptions per pod so a single inaccessible pod doesn't prevent dumping logs from
     * other pods.
     *
     * @param pods the pods to dump logs for
     */
    private void dumpPodLogs(List<Pod> pods) {
        log.error("=== POD LOGS DUMP ===");
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
}
