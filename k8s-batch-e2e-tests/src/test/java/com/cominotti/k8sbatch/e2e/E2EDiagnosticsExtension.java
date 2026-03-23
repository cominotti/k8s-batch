// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e;

import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import com.cominotti.k8sbatch.e2e.diagnostics.PodDiagnostics;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit 5 {@link TestWatcher} that automatically dumps pod diagnostics (status, logs, events) when
 * a test fails. Registered on {@link AbstractE2ETest} via {@code @ExtendWith}.
 */
public class E2EDiagnosticsExtension implements TestWatcher {

    private static final Logger log = LoggerFactory.getLogger(E2EDiagnosticsExtension.class);

    /**
     * Called by JUnit 5 when any E2E test method fails. Creates a fresh {@link PodDiagnostics}
     * instance and dumps all pod status, container logs, and namespace events to the ERROR log.
     * Catches all exceptions during diagnostics to prevent masking the original test failure.
     * The diagnostic output helps identify whether the failure was caused by the application
     * code, infrastructure (pods not ready, OOMKilled), or Kafka connectivity issues.
     *
     * @param context the extension context for the failed test
     * @param cause   the exception that caused the test failure
     */
    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.error("Test failed: {} — dumping pod diagnostics", context.getDisplayName());
        try {
            PodDiagnostics diagnostics = new PodDiagnostics(
                    K3sClusterManager.client(), K3sClusterManager.namespace());
            diagnostics.dumpAll();
        } catch (Exception e) {
            log.error("Failed to dump diagnostics", e);
        }
    }
}
