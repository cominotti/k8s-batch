package com.cominotti.k8sbatch.e2e.cluster;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Manages a shared K3s cluster for E2E tests.
 * Follows the ContainerHolder singleton pattern from integration tests.
 */
public final class K3sClusterManager {

    private static final Logger log = LoggerFactory.getLogger(K3sClusterManager.class);

    private static final String NAMESPACE = "default";
    private static final String RELEASE_NAME = "e2e";
    private static final Duration POD_READY_TIMEOUT = Duration.ofMinutes(5);

    private static volatile K3sContainer k3sContainer;
    private static volatile KubernetesClient kubernetesClient;
    private static volatile boolean clusterReady = false;
    private static volatile String currentProfile;
    private static volatile String cachedManifests;
    private static final Set<String> loadedImages = new HashSet<>();

    private K3sClusterManager() {
    }

    public static synchronized void ensureClusterRunning() {
        if (clusterReady) {
            return;
        }
        startK3s();
        clusterReady = true;
    }

    public static KubernetesClient client() {
        if (kubernetesClient == null) {
            throw new IllegalStateException("K3s cluster not started. Call ensureClusterRunning() first.");
        }
        return kubernetesClient;
    }

    public static K3sContainer container() {
        if (k3sContainer == null) {
            throw new IllegalStateException("K3s cluster not started. Call ensureClusterRunning() first.");
        }
        return k3sContainer;
    }

    public static String namespace() {
        return NAMESPACE;
    }

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

        // Render and apply Helm hooks (topic creation job)
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
     * Loads a Docker image into K3s via ctr images import.
     */
    public static synchronized void loadImage(String imageName) throws Exception {
        if (loadedImages.contains(imageName)) {
            log.debug("Image already loaded | image={}", imageName);
            return;
        }
        log.info("Loading image into K3s | image={}", imageName);

        // Save the image to a tar file on the host
        Path tempTar = Files.createTempFile("k3s-image-", ".tar");
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "save", "-o", tempTar.toString(), imageName);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Failed to save Docker image: " + imageName);
            }

            // Copy tar into K3s container and import
            k3sContainer.copyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(tempTar),
                    "/tmp/image.tar");
            var result = k3sContainer.execInContainer("ctr", "images", "import", "/tmp/image.tar");
            if (result.getExitCode() != 0) {
                log.warn("ctr images import stderr: {}", result.getStderr());
                throw new RuntimeException("Failed to import image into K3s: " + result.getStderr());
            }
            loadedImages.add(imageName);
            log.info("Image loaded into K3s | image={}", imageName);
        } finally {
            Files.deleteIfExists(tempTar);
        }
    }

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

    private static void startK3s() {
        log.info("Starting K3s container...");
        k3sContainer = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.4-k3s1"))
                .withCommand("server", "--disable=traefik");
        k3sContainer.start();
        log.info("K3s container started");

        String kubeconfig = k3sContainer.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeconfig);
        kubernetesClient = new KubernetesClientBuilder().withConfig(config).build();
        log.info("Kubernetes client connected to K3s cluster");
    }

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
        kubernetesClient.configMaps().inNamespace(NAMESPACE).resource(singleCm).createOrReplace();
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
        kubernetesClient.configMaps().inNamespace(NAMESPACE).resource(multiCm).createOrReplace();
        log.info("Created e2e-test-data-multi ConfigMap | keys={}", multiData.keySet());
    }

    private static void addResourceToConfigMap(Map<String, String> data, String key, String resourcePath) throws IOException {
        try (InputStream is = K3sClusterManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            data.put(key, new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void applyManifests(String manifests) {
        List<HasMetadata> resources = kubernetesClient.load(
                new ByteArrayInputStream(manifests.getBytes(StandardCharsets.UTF_8))).items();
        for (HasMetadata resource : resources) {
            kubernetesClient.resource(resource).inNamespace(NAMESPACE).createOrReplace();
            log.debug("Applied | kind={} | name={}", resource.getKind(), resource.getMetadata().getName());
        }
        log.info("Applied {} K8s resources", resources.size());
    }

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

    private static void waitForPodsReady() {
        log.info("Waiting for pods to become ready (timeout={})...", POD_READY_TIMEOUT);
        long deadline = System.currentTimeMillis() + POD_READY_TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            List<Pod> pods = kubernetesClient.pods().inNamespace(NAMESPACE)
                    .withLabel("app.kubernetes.io/instance", RELEASE_NAME)
                    .list().getItems();

            if (pods.isEmpty()) {
                sleep(2000);
                continue;
            }

            boolean allReady = pods.stream().allMatch(PodUtils::isReady);
            if (allReady && !pods.isEmpty()) {
                log.info("All {} pods are ready", pods.size());
                return;
            }

            for (Pod pod : pods) {
                String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                log.debug("Pod {} phase={} ready={}", pod.getMetadata().getName(), phase, PodUtils.isReady(pod));
            }
            sleep(5000);
        }

        // Timeout — dump pod status for diagnostics
        List<Pod> pods = kubernetesClient.pods().inNamespace(NAMESPACE)
                .withLabel("app.kubernetes.io/instance", RELEASE_NAME)
                .list().getItems();
        for (Pod pod : pods) {
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
            log.error("Pod not ready at timeout | name={} | phase={}", pod.getMetadata().getName(), phase);
        }
        throw new RuntimeException("Pods did not become ready within " + POD_READY_TIMEOUT);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting", e);
        }
    }

    static String resolveChartPath() {
        // Chart is at project root: helm/k8s-batch
        Path projectRoot = Path.of(System.getProperty("user.dir")).getParent();
        Path chartPath = projectRoot.resolve("helm/k8s-batch");
        if (!Files.isDirectory(chartPath)) {
            // Try from project root directly (if running from root)
            chartPath = Path.of(System.getProperty("user.dir"), "helm/k8s-batch");
        }
        if (!Files.isDirectory(chartPath)) {
            throw new IllegalStateException("Cannot find Helm chart at " + chartPath);
        }
        return chartPath.toString();
    }

    static String resolveValuesPath(String valuesFile) {
        var url = K3sClusterManager.class.getClassLoader().getResource("helm-values/" + valuesFile);
        if (url == null) {
            throw new IllegalStateException("Cannot find values file: helm-values/" + valuesFile);
        }
        return Path.of(url.getPath()).toString();
    }
}
