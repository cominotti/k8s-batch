// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e;

/**
 * Docker image constants for E2E test containers.
 * <p>
 * All image names and versions must be defined here — never hardcoded
 * in test classes, K3sClusterManager, or AbstractE2ETest.
 */
public final class E2EContainerImages {

    public static final String APP_IMAGE = "k8s-batch:e2e";
    /** Must stay in sync with {@code TestContainerImages.MYSQL_IMAGE} in the IT module. */
    public static final String MYSQL_IMAGE = "mysql:8.0";
    public static final String BUSYBOX_IMAGE = "busybox:1.36";
    public static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.9.0";
    public static final String SCHEMA_REGISTRY_IMAGE = "confluentinc/cp-schema-registry:7.9.0";
    public static final String K3S_IMAGE = "rancher/k3s:v1.31.4-k3s1";

    private E2EContainerImages() {
    }
}
