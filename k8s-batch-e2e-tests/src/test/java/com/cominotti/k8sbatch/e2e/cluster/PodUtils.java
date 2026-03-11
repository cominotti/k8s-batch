package com.cominotti.k8sbatch.e2e.cluster;

import io.fabric8.kubernetes.api.model.Pod;

public final class PodUtils {

    private PodUtils() {
    }

    public static boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }
}
