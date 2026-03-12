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
