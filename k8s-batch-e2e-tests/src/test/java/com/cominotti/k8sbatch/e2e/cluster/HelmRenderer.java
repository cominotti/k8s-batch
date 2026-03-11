// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to the Helm CLI to render templates.
 * Requires `helm` on PATH.
 */
final class HelmRenderer {

    private static final Logger log = LoggerFactory.getLogger(HelmRenderer.class);

    private HelmRenderer() {
    }

    static String render(String releaseName, String chartPath, String valuesFile) throws Exception {
        return executeHelm(releaseName, chartPath, valuesFile, false);
    }

    static String renderHooks(String releaseName, String chartPath, String valuesFile) throws Exception {
        return executeHelm(releaseName, chartPath, valuesFile, true);
    }

    private static String executeHelm(String releaseName, String chartPath, String valuesFile,
                                       boolean showOnlyHooks) throws Exception {
        ProcessBuilder pb;
        if (showOnlyHooks) {
            pb = new ProcessBuilder("helm", "template", releaseName, chartPath,
                    "-f", valuesFile, "--show-only", "templates/kafka/job-create-topics.yaml");
        } else {
            pb = new ProcessBuilder("helm", "template", releaseName, chartPath,
                    "-f", valuesFile);
        }

        // Merge stderr into stdout to avoid potential deadlock from reading streams sequentially
        pb.redirectErrorStream(true);
        log.debug("Running: {}", String.join(" ", pb.command()));

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Helm template timed out");
        }

        if (process.exitValue() != 0) {
            if (showOnlyHooks && output.contains("could not find template")) {
                log.debug("No Kafka hook template found (expected for standalone profile)");
                return "";
            }
            throw new RuntimeException("Helm template failed (exit=" + process.exitValue() + "): " + output);
        }

        log.debug("Helm template rendered | bytes={} | hooks={}", output.length(), showOnlyHooks);
        return output;
    }
}
