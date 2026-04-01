// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common.config;

import com.cominotti.k8sbatch.batch.common.domain.PathValidator;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * File access configuration for batch jobs, bound to the {@code batch.file.*} properties.
 *
 * <p>Lives in the config zone because it uses Spring Boot's {@code @ConfigurationProperties}
 * annotation, keeping the domain package free of framework dependencies. Path traversal
 * validation is delegated to the domain's {@link PathValidator}.
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
        return PathValidator.requireWithinAllowedBase(inputPath, allowedBaseDir);
    }
}
