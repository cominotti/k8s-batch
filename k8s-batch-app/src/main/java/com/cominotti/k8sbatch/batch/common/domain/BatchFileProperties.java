// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

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
     * absent or blank, ensuring the path-traversal validation logic always has a non-null base
     * to compare against.
     *
     * @param allowedBaseDir raw value from application configuration (may be {@code null} or blank)
     */
    public BatchFileProperties {
        if (allowedBaseDir == null || allowedBaseDir.isBlank()) {
            allowedBaseDir = "/";
        }
    }

    /**
     * Validates that {@code inputPath} resolves to a location within this record's
     * {@code allowedBaseDir}, preventing path traversal attacks (CWE-22).
     *
     * @param inputPath user-supplied file or directory path from job parameters
     * @return normalised absolute path, guaranteed to reside within {@code allowedBaseDir}
     * @throws IllegalArgumentException if the resolved path escapes the allowed base
     */
    public String requireWithinAllowedBase(String inputPath) {
        Path base = Path.of(allowedBaseDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(inputPath).normalize().toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                    "Input path is not within the allowed base directory | path=" + inputPath
                            + " | allowedBaseDir=" + allowedBaseDir);
        }
        return resolved.toString();
    }
}
