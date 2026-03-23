// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.cluster;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;

import java.util.List;
import java.util.Set;

/**
 * Stateless utility methods for inspecting Kubernetes pod status.
 */
public final class PodUtils {

    // ErrImagePull is intentionally absent — it's a transient pull failure that the kubelet retries,
    // eventually escalating to ImagePullBackOff (which IS terminal)
    private static final Set<String> TERMINAL_WAITING_REASONS = Set.of(
            "ImagePullBackOff", "ErrImageNeverPull", "CrashLoopBackOff", "InvalidImageName");

    private static final Set<String> TERMINAL_TERMINATED_REASONS = Set.of("OOMKilled");

    private PodUtils() {
    }

    /**
     * Returns {@code true} if the pod has a condition with type "Ready" and status "True". This
     * is the standard Kubernetes readiness check — a pod is ready when all its containers have
     * passed their readiness probes and are accepting traffic.
     *
     * @param pod the pod to check
     * @return {@code true} if the pod is ready
     */
    public static boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * Returns a terminal error description if any container (init or main) is in a
     * non-recoverable state, or {@code null} if none found.
     */
    public static String findTerminalError(Pod pod) {
        if (pod.getStatus() == null) {
            return null;
        }
        String podName = pod.getMetadata().getName();

        String error = checkContainerStatuses(podName, "initContainer",
                pod.getStatus().getInitContainerStatuses());
        if (error != null) {
            return error;
        }
        return checkContainerStatuses(podName, "container",
                pod.getStatus().getContainerStatuses());
    }

    /**
     * Returns {@code true} if the pod has condition PodScheduled=False with reason Unschedulable.
     */
    public static boolean isUnschedulable(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(c -> "PodScheduled".equals(c.getType())
                        && "False".equals(c.getStatus())
                        && "Unschedulable".equals(c.getReason()));
    }

    /**
     * Returns a human-readable description of init container progress,
     * or {@code null} if the pod has no init containers.
     * Example: {@code "2/3 done | running=wait-for-kafka"}
     */
    public static String describeInitProgress(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getInitContainerStatuses() == null) {
            return null;
        }
        List<ContainerStatus> statuses = pod.getStatus().getInitContainerStatuses();
        if (statuses.isEmpty()) {
            return null;
        }

        int completed = 0;
        String currentName = null;
        String currentState = null;

        for (ContainerStatus cs : statuses) {
            ContainerState state = cs.getState();
            if (state != null && state.getTerminated() != null
                    && state.getTerminated().getExitCode() != null
                    && state.getTerminated().getExitCode() == 0) {
                completed++;
            } else if (currentName == null) {
                currentName = cs.getName();
                currentState = describeState(state);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(completed).append("/").append(statuses.size()).append(" done");
        if (currentName != null) {
            sb.append(" | running=").append(currentName);
            if (currentState != null) {
                sb.append(" (").append(currentState).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * Returns {@code true} if at least one main (non-init) container is in Running state.
     */
    public static boolean hasRunningMainContainer(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return false;
        }
        return pod.getStatus().getContainerStatuses().stream()
                .anyMatch(cs -> cs.getState() != null && cs.getState().getRunning() != null);
    }

    /**
     * Scans a list of container statuses (init or main) for terminal waiting reasons
     * (ImagePullBackOff, ErrImageNeverPull, CrashLoopBackOff, InvalidImageName) or terminal
     * terminated reasons (OOMKilled). Returns a formatted error string identifying the pod,
     * container type, container name, and the specific failure reason, or {@code null} if no
     * terminal error is found.
     *
     * @param podName       name of the pod (used in error message formatting)
     * @param containerType {@code "initContainer"} or {@code "container"} (used only for the
     *                      error message formatting)
     * @param statuses      list of container statuses to scan, may be {@code null}
     * @return formatted error string, or {@code null} if no terminal error is found
     */
    private static String checkContainerStatuses(String podName, String containerType,
                                                  List<ContainerStatus> statuses) {
        if (statuses == null) {
            return null;
        }
        for (ContainerStatus cs : statuses) {
            ContainerState state = cs.getState();
            if (state == null) {
                continue;
            }
            if (state.getWaiting() != null && state.getWaiting().getReason() != null
                    && TERMINAL_WAITING_REASONS.contains(state.getWaiting().getReason())) {
                return podName + " " + containerType + "=" + cs.getName()
                        + " | " + state.getWaiting().getReason()
                        + ": " + state.getWaiting().getMessage();
            }
            if (state.getTerminated() != null && state.getTerminated().getReason() != null
                    && TERMINAL_TERMINATED_REASONS.contains(state.getTerminated().getReason())) {
                return podName + " " + containerType + "=" + cs.getName()
                        + " | " + state.getTerminated().getReason()
                        + " (exit=" + state.getTerminated().getExitCode() + ")";
            }
        }
        return null;
    }

    /**
     * Maps a Kubernetes {@link ContainerState} to a concise human-readable string for progress
     * logging. Returns {@code "running"}, {@code "waiting:Reason"},
     * {@code "terminated:exit=N"}, or {@code "unknown"} depending on which state field is set.
     * Returns {@code null} if state itself is {@code null}.
     *
     * @param state the container state to describe, may be {@code null}
     * @return human-readable state description, or {@code null} if state is {@code null}
     */
    private static String describeState(ContainerState state) {
        if (state == null) {
            return null;
        }
        if (state.getRunning() != null) {
            return "running";
        }
        if (state.getWaiting() != null) {
            return "waiting" + (state.getWaiting().getReason() != null
                    ? ":" + state.getWaiting().getReason() : "");
        }
        if (state.getTerminated() != null) {
            return "terminated:exit=" + state.getTerminated().getExitCode();
        }
        return "unknown";
    }
}
