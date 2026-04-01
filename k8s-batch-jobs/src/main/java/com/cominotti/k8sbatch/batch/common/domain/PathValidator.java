// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common.domain;

import java.nio.file.Path;

/**
 * Path traversal prevention utility (CWE-22). Pure domain validation — no framework dependencies.
 */
public final class PathValidator {

    private PathValidator() {
    }

    /**
     * Validates that {@code inputPath} resolves to a location within the given
     * {@code allowedBaseDir}, preventing path traversal attacks (CWE-22).
     *
     * @param inputPath      user-supplied file or directory path from job parameters
     * @param allowedBaseDir canonical base directory that all job input file paths must reside in
     * @return normalised absolute path, guaranteed to reside within {@code allowedBaseDir}
     * @throws IllegalArgumentException if the resolved path escapes the allowed base
     */
    public static String requireWithinAllowedBase(String inputPath, String allowedBaseDir) {
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
