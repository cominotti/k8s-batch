// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.cluster;

import com.cominotti.k8sbatch.e2e.diagnostics.PodDiagnostics;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.cominotti.k8sbatch.e2e.E2EContainerImages;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manages a shared K3s cluster for E2E tests.
 * Follows the ContainerHolder singleton pattern from integration tests.
 *
 * <p>Image loading is delegated to {@link K3sImageLoader}, which supports parallel
 * and batch loading. The K3s container has a {@value #K3S_MEMORY_LIMIT_MB}MB memory
 * ceiling to prevent host-level OOM — resource exhaustion surfaces as pod-level
 * OOMKilled (detected by {@link DeploymentWaiter}) instead of Docker daemon kills.
 */
public final class K3sClusterManager {

    private static final Logger log = LoggerFactory.getLogger(K3sClusterManager.class);

    private static final String NAMESPACE = "default";
    private static final String RELEASE_NAME = "e2e";
    private static final Duration POD_READY_TIMEOUT = Duration.ofMinutes(5);

    /** Memory ceiling for the K3s container. Prevents host-level OOM. */
    private static final long K3S_MEMORY_LIMIT_MB = 6 * 1024;
    private static final long K3S_MEMORY_LIMIT_BYTES = K3S_MEMORY_LIMIT_MB * 1024 * 1024;

    private static volatile K3sContainer k3sContainer;
    private static volatile KubernetesClient kubernetesClient;
    private static volatile boolean clusterReady = false;
    private static volatile String currentProfile;
    // Manifests are cached so teardownDeployment() can delete the same resources it created
    // (we use raw kubectl-equivalent apply, not helm install, so there's no helm release to uninstall)
    private static volatile String cachedManifests;

    private K3sClusterManager() {
    }

    /**
     * Ensures the K3s cluster is running, starting it if necessary.
     *
     * <p>Thread-safe and idempotent: the first call starts the cluster and sets the
     * {@code clusterReady} volatile flag. Subsequent calls return immediately without
     * acquiring the lock (volatile read fast-path), while concurrent first calls are
     * serialized by the {@code synchronized} block.
     */
    public static synchronized void ensureClusterRunning() {
        if (clusterReady) {
            return;
        }
        startK3s();
        clusterReady = true;
    }

    /**
     * Returns the Fabric8 {@link KubernetesClient} connected to the K3s cluster.
     *
     * @return the Kubernetes client for the running K3s cluster
     * @throws IllegalStateException if the cluster has not been started via
     *     {@link #ensureClusterRunning()}
     */
    public static KubernetesClient client() {
        if (kubernetesClient == null) {
            throw new IllegalStateException("K3s cluster not started. Call ensureClusterRunning() first.");
        }
        return kubernetesClient;
    }

    /**
     * Returns the underlying Testcontainers {@link K3sContainer}.
     *
     * @return the running K3s container
     * @throws IllegalStateException if the cluster has not been started via
     *     {@link #ensureClusterRunning()}
     */
    public static K3sContainer container() {
        if (k3sContainer == null) {
            throw new IllegalStateException("K3s cluster not started. Call ensureClusterRunning() first.");
        }
        return k3sContainer;
    }

    /**
     * Returns the Kubernetes namespace used for all E2E resources.
     *
     * @return always {@code "default"}
     */
    public static String namespace() {
        return NAMESPACE;
    }

    /**
     * Returns the Helm release name used in all E2E deployments.
     * Used as the label value for {@code app.kubernetes.io/instance}.
     *
     * @return always {@code "e2e"}
     */
    public static String releaseName() {
        return RELEASE_NAME;
    }

    /**
     * Deploys the Helm chart with the specified values file.
     * If a different profile is already deployed, tears down first.
     */
    public static synchronized void deploy(String valuesFile) throws Exception {
        ensureClusterRunning();

        if (currentProfile != null && !currentProfile.equals(valuesFile)) {
            log.info("Profile change detected | old={} | new={} — tearing down", currentProfile, valuesFile);
            teardownDeployment();
        }

        if (currentProfile != null && currentProfile.equals(valuesFile)) {
            log.info("Deployment already active for profile={}", valuesFile);
            return;
        }

        log.info("Deploying Helm chart | valuesFile={}", valuesFile);

        // Create test-data ConfigMap
        createTestDataConfigMaps();

        // Render and apply Helm chart
        String chartPath = resolveChartPath();
        String valuesPath = resolveValuesPath(valuesFile);

        cachedManifests = HelmRenderer.render(RELEASE_NAME, chartPath, valuesPath);
        applyManifests(cachedManifests);

        // Render and apply Helm hooks (topic creation job).
        // Hooks (e.g., pre-install Jobs like Kafka topic creation) are rendered separately
        // because we bypass Helm's hook lifecycle — we apply raw manifests via Fabric8, so
        // hooks must be applied explicitly after the main resources.
        String hooks = HelmRenderer.renderHooks(RELEASE_NAME, chartPath, valuesPath);
        if (hooks != null && !hooks.isBlank()) {
            applyManifests(hooks);
        }

        // Wait for pods to be ready
        waitForPodsReady();

        currentProfile = valuesFile;
        log.info("Deployment complete | profile={}", valuesFile);
    }

    /**
     * Loads a single Docker image into K3s.
     * Delegates to {@link K3sImageLoader#loadImage(K3sContainer, String)}.
     *
     * @param imageName the Docker image name (e.g., {@code "mysql:8.0"})
     * @throws Exception if saving, copying, or importing the image fails
     */
    public static void loadImage(String imageName) throws Exception {
        K3sImageLoader.loadImage(container(), imageName);
    }

    /**
     * Loads multiple Docker images into K3s in parallel with batch optimization.
     * Delegates to {@link K3sImageLoader#loadImages(K3sContainer, List)}.
     *
     * @param imageNames the Docker image names to load
     * @throws Exception if any image fails to load
     */
    public static void loadImages(List<String> imageNames) throws Exception {
        K3sImageLoader.loadImages(container(), imageNames);
    }

    /**
     * Tears down the current Helm deployment.
     *
     * <p>Deletes cached Kubernetes manifests and test-data ConfigMaps, then waits up to
     * 2 minutes for all release-labeled pods to terminate. Resets {@code currentProfile}
     * so a subsequent {@link #deploy(String)} call triggers a fresh deployment.
     *
     * <p>No-op if no deployment is currently active.
     */
    public static synchronized void teardownDeployment() {
        if (currentProfile == null) {
            return;
        }
        log.info("Tearing down deployment | profile={}", currentProfile);
        try {
            if (cachedManifests != null) {
                deleteManifests(cachedManifests);
            }

            // Delete test-data configmaps
            kubernetesClient.configMaps().inNamespace(NAMESPACE)
                    .withName("e2e-test-data").delete();
            kubernetesClient.configMaps().inNamespace(NAMESPACE)
                    .withName("e2e-test-data-multi").delete();

            // Wait for pods to terminate
            kubernetesClient.pods().inNamespace(NAMESPACE)
                    .withLabel("app.kubernetes.io/instance", RELEASE_NAME)
                    .waitUntilCondition(p -> p == null, 2, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Error during teardown", e);
        }
        currentProfile = null;
        cachedManifests = null;
    }

    /**
     * Creates and starts the K3s container.
     *
     * <p>Configures the container with Traefik disabled (the Helm chart uses NodePort/ClusterIP,
     * not Ingress) and a {@value #K3S_MEMORY_LIMIT_MB}MB memory ceiling to prevent host-level
     * OOM. Builds a Fabric8 {@link KubernetesClient} from the container's generated kubeconfig.
     *
     * <p>Resets the image loader's dedup set via {@link K3sImageLoader#resetLoadedImages()}
     * since the new containerd instance has no pre-loaded images.
     */
    private static void startK3s() {
        log.info("Starting K3s container | memoryLimit={}MB", K3S_MEMORY_LIMIT_MB);
        k3sContainer = new K3sContainer(DockerImageName.parse(E2EContainerImages.K3S_IMAGE))
                // Traefik disabled — the Helm chart uses NodePort/ClusterIP, not Ingress
                .withCommand("server", "--disable=traefik")
                // Memory ceiling: OOM surfaces as pod OOMKilled (detected by DeploymentWaiter)
                // instead of an opaque Docker daemon kill at the host level
                .withCreateContainerCmdModifier(cmd ->
                        cmd.getHostConfig().withMemory(K3S_MEMORY_LIMIT_BYTES));
        k3sContainer.start();
        // New K3s containerd instance has no images — clear the dedup set from any previous cluster
        K3sImageLoader.resetLoadedImages();
        log.info("K3s container started | memoryLimit={}MB", K3S_MEMORY_LIMIT_MB);

        String kubeconfig = k3sContainer.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeconfig);
        kubernetesClient = new KubernetesClientBuilder().withConfig(config).build();
        log.info("Kubernetes client connected to K3s cluster");
    }

    /**
     * Creates two ConfigMaps from classpath CSV resources for E2E test data.
     *
     * <p>{@code e2e-test-data} contains single-file CSVs (10-row and 100-row samples
     * mounted at {@code /data/test/}) and {@code e2e-test-data-multi} contains multi-file
     * CSVs ({@code file-a/b/c.csv} mounted at {@code /data/test/multi/}).
     *
     * <p>Uses {@code createOrUpdate} to be idempotent across profile switches.
     *
     * @throws IOException if any CSV resource is not found on the classpath
     */
    private static void createTestDataConfigMaps() throws IOException {
        // Single-file test data (mounted at /data/test/)
        Map<String, String> singleData = new HashMap<>();
        addResourceToConfigMap(singleData, "sample-10rows.csv", "test-data/csv/single/sample-10rows.csv");
        addResourceToConfigMap(singleData, "sample-100rows.csv", "test-data/csv/single/sample-100rows.csv");

        ConfigMap singleCm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName("e2e-test-data")
                    .withNamespace(NAMESPACE)
                .endMetadata()
                .withData(singleData)
                .build();
        kubernetesClient.configMaps().inNamespace(NAMESPACE).resource(singleCm).unlock().createOr(NonDeletingOperation::update);
        log.info("Created e2e-test-data ConfigMap | keys={}", singleData.keySet());

        // Multi-file test data (mounted at /data/test/multi/)
        Map<String, String> multiData = new HashMap<>();
        addResourceToConfigMap(multiData, "file-a.csv", "test-data/csv/multi/file-a.csv");
        addResourceToConfigMap(multiData, "file-b.csv", "test-data/csv/multi/file-b.csv");
        addResourceToConfigMap(multiData, "file-c.csv", "test-data/csv/multi/file-c.csv");

        ConfigMap multiCm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName("e2e-test-data-multi")
                    .withNamespace(NAMESPACE)
                .endMetadata()
                .withData(multiData)
                .build();
        kubernetesClient.configMaps().inNamespace(NAMESPACE).resource(multiCm).unlock().createOr(NonDeletingOperation::update);
        log.info("Created e2e-test-data-multi ConfigMap | keys={}", multiData.keySet());
    }

    /**
     * Reads a classpath resource and adds it as a String entry in the ConfigMap data map.
     *
     * @param data         the mutable data map to add the entry to
     * @param key          the ConfigMap data key (e.g., {@code "sample-10rows.csv"})
     * @param resourcePath the classpath resource path to read
     * @throws IOException if the resource is not found on the classpath
     */
    private static void addResourceToConfigMap(Map<String, String> data, String key, String resourcePath) throws IOException {
        try (InputStream is = K3sClusterManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            data.put(key, new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Parses a multi-document YAML string into Kubernetes resources and applies each one
     * via Fabric8's {@code createOrUpdate}.
     *
     * <p>Used for both main chart manifests and hook manifests.
     *
     * @param manifests the multi-document YAML string rendered by {@link HelmRenderer}
     */
    private static void applyManifests(String manifests) {
        List<HasMetadata> resources = kubernetesClient.load(
                new ByteArrayInputStream(manifests.getBytes(StandardCharsets.UTF_8))).items();
        for (HasMetadata resource : resources) {
            kubernetesClient.resource(resource).inNamespace(NAMESPACE).unlock().createOr(NonDeletingOperation::update);
            log.debug("Applied | kind={} | name={}", resource.getKind(), resource.getMetadata().getName());
        }
        log.info("Applied {} K8s resources", resources.size());
    }

    /**
     * Parses the multi-document YAML string and deletes each resource.
     *
     * <p>Ignores individual delete errors since a resource may already be gone during
     * teardown (e.g., cascade-deleted by an owner reference).
     *
     * @param manifests the multi-document YAML string to parse and delete
     */
    private static void deleteManifests(String manifests) {
        List<HasMetadata> resources = kubernetesClient.load(
                new ByteArrayInputStream(manifests.getBytes(StandardCharsets.UTF_8))).items();
        for (HasMetadata resource : resources) {
            try {
                kubernetesClient.resource(resource).inNamespace(NAMESPACE).delete();
            } catch (Exception e) {
                log.debug("Ignoring delete error for {} {}: {}", resource.getKind(),
                        resource.getMetadata().getName(), e.getMessage());
            }
        }
    }

    /**
     * Creates a {@link DeploymentWaiter} with {@link PodDiagnostics} and waits up to
     * {@link #POD_READY_TIMEOUT} (5 minutes) for all release-labeled pods to become ready.
     */
    private static void waitForPodsReady() {
        PodDiagnostics diagnostics = new PodDiagnostics(kubernetesClient, NAMESPACE);
        DeploymentWaiter waiter = new DeploymentWaiter(
                kubernetesClient, NAMESPACE, RELEASE_NAME, diagnostics);
        waiter.waitForPodsReady(POD_READY_TIMEOUT);
    }

    /**
     * Locates the Helm chart directory.
     *
     * <p>Tries {@code ../helm/k8s-batch} first (when CWD is the e2e submodule directory),
     * then {@code ./helm/k8s-batch} (when CWD is the project root).
     *
     * @return the absolute path to the Helm chart directory
     * @throws IllegalStateException if the chart is not found at either location
     */
    static String resolveChartPath() {
        // First path: running from the e2e-tests submodule directory (parent = project root)
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path chartPath = projectRoot.resolve("helm/k8s-batch");
        if (!Files.isDirectory(chartPath)) {
            // Second path: running from the project root directly
            chartPath = Path.of(System.getProperty("user.dir"), "helm/k8s-batch");
        }
        if (!Files.isDirectory(chartPath)) {
            throw new IllegalStateException("Cannot find Helm chart at " + chartPath);
        }
        return chartPath.toString();
    }

    /**
     * Resolves a Helm values file from the classpath {@code helm-values/} directory.
     *
     * @param valuesFile the values file name (e.g., {@code "values-remote.yaml"})
     * @return the absolute filesystem path to the resolved values file
     * @throws IllegalStateException if the file is not found on the classpath
     */
    static String resolveValuesPath(String valuesFile) {
        var url = K3sClusterManager.class.getClassLoader().getResource("helm-values/" + valuesFile);
        if (url == null) {
            throw new IllegalStateException("Cannot find values file: helm-values/" + valuesFile);
        }
        return Path.of(url.getPath()).toString();
    }
}
