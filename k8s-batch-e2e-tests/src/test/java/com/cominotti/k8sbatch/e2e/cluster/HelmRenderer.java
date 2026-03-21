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

    /**
     * Renders all non-hook templates from the Helm chart by shelling out to {@code helm template}.
     * Returns the rendered YAML as a string for downstream processing by
     * {@link K3sClusterManager#applyManifests}.
     *
     * @param releaseName Helm release name
     * @param chartPath   path to the chart directory
     * @param valuesFile  path to the values YAML
     * @return rendered multi-document YAML
     * @throws Exception if helm exits non-zero or times out
     */
    static String render(String releaseName, String chartPath, String valuesFile) throws Exception {
        return executeHelm(releaseName, chartPath, valuesFile, false);
    }

    /**
     * Renders only Helm hooks (pre-install Jobs like the Kafka topic-creation Job). Hooks are
     * rendered separately because the E2E test applies raw manifests via kubectl-equivalent,
     * bypassing Helm's hook lifecycle semantics.
     */
    static String renderHooks(String releaseName, String chartPath, String valuesFile) throws Exception {
        return executeHelm(releaseName, chartPath, valuesFile, true);
    }

    /**
     * Builds and executes a {@code helm template} process. When {@code showOnlyHooks} is
     * {@code true}, adds {@code --show-only templates/kafka/job-create-topics.yaml} to render
     * only the Kafka topic-creation hook. Merges stderr into stdout via
     * {@link ProcessBuilder#redirectErrorStream(boolean)} to prevent deadlock from sequential
     * stream reads. Waits up to 60 seconds for process completion.
     *
     * <p>For hook rendering, a "could not find template" error is expected (standalone profile
     * has no Kafka hook) and returns empty string instead of throwing.
     *
     * @param releaseName   Helm release name
     * @param chartPath     path to the chart directory
     * @param valuesFile    path to the values YAML
     * @param showOnlyHooks if {@code true}, renders only the Kafka topic-creation hook template
     * @return rendered YAML, or empty string if hook template is absent
     * @throws RuntimeException if helm exits non-zero (unless expected hook absence) or times out
     * @throws Exception        if the process cannot be started
     */
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
