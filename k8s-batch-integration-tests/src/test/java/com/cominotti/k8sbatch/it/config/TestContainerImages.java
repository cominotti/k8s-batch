// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

/**
 * Docker image constants for integration test containers.
 * <p>
 * All image names and versions must be defined here — never hardcoded
 * in container constructors or log messages.
 */
public final class TestContainerImages {

    /** Must stay in sync with {@code E2EContainerImages.MYSQL_IMAGE} in the E2E module. */
    public static final String MYSQL_IMAGE = "mysql:8.0";
    /** IT-module only — Redpanda is not used in E2E tests (which use real Confluent Kafka). */
    public static final String REDPANDA_IMAGE = "docker.redpanda.com/redpandadata/redpanda:v25.1.9";

    private TestContainerImages() {
    }
}
