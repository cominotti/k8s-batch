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

    /**
     * Main polling loop that waits for all Helm release pods to become ready.
     *
     * <p>Runs five detection features on each poll cycle:
     * <ol>
     *   <li>Terminal waiting errors (ImagePullBackOff, CrashLoopBackOff, OOMKilled)</li>
     *   <li>Unschedulable pods (insufficient CPU/memory on the K3s node)</li>
     *   <li>Init container progress logging (deduplicated)</li>
     *   <li>Progress stall detection (no new ready pods or init completions within
     *       {@link #STALL_TIMEOUT})</li>
     *   <li>Log streaming for pods stuck beyond {@link #LOG_STREAM_THRESHOLD}</li>
     * </ol>
     *
     * <p>Fails fast on terminal errors and stalls rather than waiting for the full timeout.
     * On timeout, dumps diagnostics via {@link PodDiagnostics} before throwing.
     *
     * @param timeout maximum time to wait for all pods to become ready
     * @throws RuntimeException if pods do not become ready within the timeout, or if a
     *     terminal error or stall is detected
     */
    void waitForPodsReady(Duration timeout) {
        log.info("Waiting for pods to become ready | timeout={}", timeout);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        lastProgressTimestamp = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            List<Pod> pods = listReleasePods();

            if (pods.isEmpty()) {
                // Give pods time to appear in the API — K3s may still be processing the applied manifests
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

    /**
     * Checks a single pod for non-recoverable container states via
     * {@link PodUtils#findTerminalError(Pod)}.
     *
     * <p>If a terminal error is found, dumps diagnostics for all pods and throws
     * immediately — there is no point waiting for a pod stuck in ImagePullBackOff
     * or CrashLoopBackOff.
     *
     * @param pod     the pod to inspect
     * @param allPods all release pods (passed to diagnostics on failure)
     * @throws RuntimeException if the pod has a terminal error
     */
    private void checkTerminalErrors(Pod pod, List<Pod> allPods) {
        String error = PodUtils.findTerminalError(pod);
        if (error != null) {
            log.error("Terminal error detected | {}", error);
            failWithDiagnostics("Terminal pod error: " + error, allPods);
        }
    }

    /**
     * Checks if a pod has the {@code PodScheduled=False} condition with reason
     * {@code Unschedulable}.
     *
     * <p>This typically means the K3s node lacks sufficient CPU or memory for the pod's
     * resource requests — a configuration issue that will not resolve by waiting.
     *
     * @param pod     the pod to inspect
     * @param allPods all release pods (passed to diagnostics on failure)
     * @throws RuntimeException if the pod is unschedulable
     */
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

    /**
     * Logs init container progress for non-ready pods using
     * {@link PodUtils#describeInitProgress(Pod)}.
     *
     * <p>Deduplicates output by only logging when a pod's progress description changes
     * (tracked via {@link #lastLoggedProgress}). Clears tracking for pods that become ready.
     *
     * @param pods all release pods to inspect
     */
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

    /**
     * Streams container logs for pods stuck in a not-ready state beyond
     * {@link #LOG_STREAM_THRESHOLD} (60 seconds).
     *
     * <p>Tails the last {@value #LOG_STREAM_LINES} lines of the main container's logs.
     * Only streams once per pod (tracked via {@link #logsAlreadyStreamed}). Requires the
     * main container to be running — cannot tail logs for containers still in init phase.
     *
     * @param pods all release pods to inspect
     */
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

            // Only tail logs when the main container is running — init containers run before
            // the main container starts, and tailing would fail or return empty
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

    /**
     * Detects deployment stalls by tracking two progress metrics: ready pod count and
     * completed init container count.
     *
     * <p>If neither metric improves within {@link #STALL_TIMEOUT} (120 seconds), the
     * deployment is considered stalled. Both metrics must stall simultaneously — init
     * container completions alone count as progress even if no new pods become fully ready.
     *
     * @param pods       all release pods
     * @param readyCount number of pods currently in ready state
     * @throws RuntimeException if the deployment is stalled
     */
    private void checkProgressStall(List<Pod> pods, int readyCount) {
        int initCompletedCount = countCompletedInitContainers(pods);

        // Either metric advancing counts as progress — init completions matter because a pod
        // with 3 init containers (e.g., wait-for-mysql, wait-for-kafka, wait-for-schema-registry)
        // progresses through them before the main container can become ready
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

    /**
     * Dumps pod diagnostics (status, logs, events) via {@link PodDiagnostics}, then throws
     * a {@link RuntimeException}.
     *
     * <p>Called by all failure paths to ensure diagnostic output is available before the
     * exception propagates.
     *
     * @param message the error message for the exception
     * @param pods    all release pods to include in the diagnostic dump
     * @throws RuntimeException always
     */
    private void failWithDiagnostics(String message, List<Pod> pods) {
        diagnostics.dumpForPods(pods);
        throw new RuntimeException(message);
    }

    /**
     * Lists release pods, excluding completed Job pods.
     *
     * <p>Kubernetes sets {@code pod.status.phase = "Succeeded"} for Job pods that exit
     * normally (e.g., the Kafka topic-creation Job). These pods carry the same
     * {@code app.kubernetes.io/instance} label as long-running pods, so they appear
     * in the pod list. However, a Succeeded pod never has a {@code Ready=True} condition,
     * which makes the {@code readyCount == pods.size()} termination check impossible
     * to satisfy — causing a false stall timeout after 120s.
     *
     * @return list of non-terminated pods belonging to this Helm release
     */
    private List<Pod> listReleasePods() {
        return client.pods().inNamespace(namespace)
                .withLabel("app.kubernetes.io/instance", releaseLabel)
                .list().getItems().stream()
                .filter(pod -> !"Succeeded".equals(pod.getStatus().getPhase()))
                .toList();
    }

    /**
     * Counts init containers across all pods that have terminated successfully (exit code 0).
     *
     * <p>Used by {@link #checkProgressStall(List, int)} to track init progress separately
     * from overall pod readiness.
     *
     * @param pods all release pods to inspect
     * @return total number of successfully completed init containers
     */
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

    /**
     * Counts all init containers declared in pod specs.
     *
     * <p>Used alongside {@link #countCompletedInitContainers(List)} for the progress ratio
     * in stall error messages.
     *
     * @param pods all release pods to inspect
     * @return total number of declared init containers across all pods
     */
    private static int countTotalInitContainers(List<Pod> pods) {
        int count = 0;
        for (Pod pod : pods) {
            if (pod.getSpec() != null && pod.getSpec().getInitContainers() != null) {
                count += pod.getSpec().getInitContainers().size();
            }
        }
        return count;
    }

    /**
     * {@link Thread#sleep(long)} wrapper that converts {@link InterruptedException} to
     * {@link RuntimeException} while preserving the interrupt flag via
     * {@link Thread#currentThread()}.{@link Thread#interrupt() interrupt()}.
     *
     * @param duration the duration to sleep
     * @throws RuntimeException if the thread is interrupted during sleep
     */
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for pods", e);
        }
    }
}
