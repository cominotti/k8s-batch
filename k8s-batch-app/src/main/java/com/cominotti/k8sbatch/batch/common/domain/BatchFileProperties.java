// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File access configuration for batch jobs, bound to the {@code batch.file.*} properties.
 *
 * <p>Input file paths supplied via job parameters are validated to ensure they reside within
 * {@code allowedBaseDir}, preventing path traversal attacks (CWE-22). Set this to the actual
 * data volume mount path in production (e.g. {@code /data}) to enforce a strict boundary.
 * The default {@code /} allows any valid absolute path and is suitable for local development
 * and integration testing.
 *
 * @param allowedBaseDir canonical base directory that all job input file paths must reside in.
 *                       Defaults to {@code /} (system root) when not explicitly configured.
 */
@ConfigurationProperties(prefix = "batch.file")
public record BatchFileProperties(String allowedBaseDir) {

    /**
     * Normalises the configured base directory. Falls back to {@code /} when the property is
     * absent or blank, ensuring the validation logic always has a non-null base to compare against.
     *
     * @param allowedBaseDir raw value from application configuration
     */
    public BatchFileProperties {
        if (allowedBaseDir == null || allowedBaseDir.isBlank()) {
            allowedBaseDir = "/";
        }
    }
}
