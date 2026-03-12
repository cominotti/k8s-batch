// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.cluster;

import com.cominotti.k8sbatch.e2e.diagnostics.PodDiagnostics;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Waits for all pods of a Helm release to become ready, with fast failure detection.
 * <p>
 * Detects terminal errors (ImagePullBackOff, CrashLoopBackOff, OOMKilled, etc.)
 * and unschedulable pods immediately. Tracks forward progress and fails early
 * if the deployment stalls. Streams container logs for pods stuck beyond a threshold.
 */
final class DeploymentWaiter {

    private static final Logger log = LoggerFactory.getLogger(DeploymentWaiter.class);

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration INITIAL_WAIT = Duration.ofSeconds(2);
    // 120s matches the remote partitioning @Timeout in integration tests
    private static final Duration STALL_TIMEOUT = Duration.ofSeconds(120);
    // Start streaming logs after 60s to help diagnose stuck pods without overwhelming output
    private static final Duration LOG_STREAM_THRESHOLD = Duration.ofSeconds(60);
    private static final int LOG_STREAM_LINES = 50;

    private final KubernetesClient client;
    private final String namespace;
    private final String releaseLabel;
    private final PodDiagnostics diagnostics;

    // Detection features tracked by numbered comments in waitForPodsReady():
    //   #1 Terminal waiting errors (ImagePullBackOff, CrashLoopBackOff, etc.)
    //   #2 Unschedulable pods
    //   #3 Init container progress logging (deduped)
    //   #4 Progress stall detection (no new ready pods or init completions within STALL_TIMEOUT)
    //   #5 Log streaming for pods stuck beyond LOG_STREAM_THRESHOLD

    // Progress stall tracking (#4)
    private int lastReadyCount;
    private int lastInitCompletedCount;
    private long lastProgressTimestamp;

    // Log streaming tracking (#5)
    private final Map<String, Long> podFirstSeenNotReady = new HashMap<>();
    private final Set<String> logsAlreadyStreamed = new HashSet<>();

    // Init progress dedup (#3) — only log when progress changes
    private final Map<String, String> lastLoggedProgress = new HashMap<>();

    DeploymentWaiter(KubernetesClient client, String namespace,
                     String releaseLabel, PodDiagnostics diagnostics) {
        this.client = client;
        this.namespace = namespace;
        this.releaseLabel = releaseLabel;
        this.diagnostics = diagnostics;
    }

    void waitForPodsReady(Duration timeout) {
        log.info("Waiting for pods to become ready | timeout={}", timeout);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        lastProgressTimestamp = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            List<Pod> pods = listReleasePods();

            if (pods.isEmpty()) {
                sleep(INITIAL_WAIT);
                continue;
            }

            // #1 + #2: Check for terminal errors on each pod
            for (Pod pod : pods) {
                checkTerminalErrors(pod, pods);
                checkUnschedulable(pod, pods);
            }

            // #3: Log init container progress for non-ready pods
            logInitProgress(pods);

            // Compute ready count once — used for both the all-ready check and stall detection
            int readyCount = (int) pods.stream().filter(PodUtils::isReady).count();

            if (readyCount == pods.size()) {
                log.info("All {} pods are ready", pods.size());
                return;
            }

            // #5: Stream logs for pods stuck beyond threshold
            streamLogsForStuckPods(pods);

            // #4: Check for progress stall
            checkProgressStall(pods, readyCount);

            sleep(POLL_INTERVAL);
        }

        // Timeout — dump diagnostics and fail
        List<Pod> pods = listReleasePods();
        log.error("Pod readiness timeout | timeout={} | pods={}", timeout, pods.size());
        failWithDiagnostics("Pods did not become ready within " + timeout, pods);
    }

    private void checkTerminalErrors(Pod pod, List<Pod> allPods) {
        String error = PodUtils.findTerminalError(pod);
        if (error != null) {
            log.error("Terminal error detected | {}", error);
            failWithDiagnostics("Terminal pod error: " + error, allPods);
        }
    }

    private void checkUnschedulable(Pod pod, List<Pod> allPods) {
        if (PodUtils.isUnschedulable(pod)) {
            String podName = pod.getMetadata().getName();
            String message = pod.getStatus().getConditions().stream()
                    .filter(c -> "PodScheduled".equals(c.getType()))
                    .map(c -> c.getMessage() != null ? c.getMessage() : "no details")
                    .findFirst()
                    .orElse("no details");
            log.error("Pod unschedulable | pod={} | message={}", podName, message);
            failWithDiagnostics("Pod unschedulable: " + podName + " — " + message, allPods);
        }
    }

    private void logInitProgress(List<Pod> pods) {
        for (Pod pod : pods) {
            if (PodUtils.isReady(pod)) {
                lastLoggedProgress.remove(pod.getMetadata().getName());
                continue;
            }
            String progress = PodUtils.describeInitProgress(pod);
            if (progress != null) {
                String podName = pod.getMetadata().getName();
                if (!progress.equals(lastLoggedProgress.get(podName))) {
                    log.info("Init progress | pod={} | {}", podName, progress);
                    lastLoggedProgress.put(podName, progress);
                }
            }
        }
    }

    private void streamLogsForStuckPods(List<Pod> pods) {
        long now = System.currentTimeMillis();
        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();

            if (PodUtils.isReady(pod)) {
                podFirstSeenNotReady.remove(podName);
                continue;
            }

            podFirstSeenNotReady.putIfAbsent(podName, now);
            long notReadyDuration = now - podFirstSeenNotReady.get(podName);

            if (notReadyDuration > LOG_STREAM_THRESHOLD.toMillis()
                    && !logsAlreadyStreamed.contains(podName)
                    && PodUtils.hasRunningMainContainer(pod)) {
                log.warn("Pod not ready for {}s — streaming logs | pod={}",
                        notReadyDuration / 1000, podName);
                try {
                    String logs = client.pods().inNamespace(namespace)
                            .withName(podName)
                            .tailingLines(LOG_STREAM_LINES)
                            .getLog();
                    log.warn("Container logs for pod {}:\n{}", podName, logs);
                } catch (Exception e) {
                    log.debug("Cannot stream logs for pod {} : {}", podName, e.getMessage());
                }
                logsAlreadyStreamed.add(podName);
            }
        }
    }

    private void checkProgressStall(List<Pod> pods, int readyCount) {
        int initCompletedCount = countCompletedInitContainers(pods);

        if (readyCount > lastReadyCount || initCompletedCount > lastInitCompletedCount) {
            lastReadyCount = readyCount;
            lastInitCompletedCount = initCompletedCount;
            lastProgressTimestamp = System.currentTimeMillis();
        }

        long stallDuration = System.currentTimeMillis() - lastProgressTimestamp;
        if (stallDuration > STALL_TIMEOUT.toMillis()) {
            int totalInitContainers = countTotalInitContainers(pods);
            log.error("Deployment stalled — no progress for {}s | readyPods={}/{} | initContainers={}/{}",
                    stallDuration / 1000, readyCount, pods.size(),
                    initCompletedCount, totalInitContainers);
            failWithDiagnostics(
                    "Deployment stalled: no progress for " + stallDuration / 1000 + "s"
                            + " (readyPods=" + readyCount + "/" + pods.size()
                            + ", initContainers=" + initCompletedCount + "/" + totalInitContainers + ")",
                    pods);
        }
    }

    private void failWithDiagnostics(String message, List<Pod> pods) {
        diagnostics.dumpForPods(pods);
        throw new RuntimeException(message);
    }

    private List<Pod> listReleasePods() {
        return client.pods().inNamespace(namespace)
                .withLabel("app.kubernetes.io/instance", releaseLabel)
                .list().getItems();
    }

    private static int countCompletedInitContainers(List<Pod> pods) {
        int count = 0;
        for (Pod pod : pods) {
            if (pod.getStatus() == null || pod.getStatus().getInitContainerStatuses() == null) {
                continue;
            }
            for (ContainerStatus cs : pod.getStatus().getInitContainerStatuses()) {
                if (cs.getState() != null && cs.getState().getTerminated() != null
                        && cs.getState().getTerminated().getExitCode() != null
                        && cs.getState().getTerminated().getExitCode() == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countTotalInitContainers(List<Pod> pods) {
        int count = 0;
        for (Pod pod : pods) {
            if (pod.getSpec() != null && pod.getSpec().getInitContainers() != null) {
                count += pod.getSpec().getInitContainers().size();
            }
        }
        return count;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for pods", e);
        }
    }
}
