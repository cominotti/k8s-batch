package com.cominotti.k8sbatch.e2e;

import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import com.cominotti.k8sbatch.e2e.diagnostics.PodDiagnostics;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class E2EDiagnosticsExtension implements TestWatcher {

    private static final Logger log = LoggerFactory.getLogger(E2EDiagnosticsExtension.class);

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
