// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Loads Docker images into K3s via {@code ctr images import} with parallel execution
 * and batch {@code docker save} support.
 *
 * <p>Parallel loading uses a fixed thread pool (max 4 threads) to overlap I/O-bound
 * {@code docker save} + {@code copyFileToContainer} + {@code ctr import} pipelines.
 * Each thread uses a unique container-side path to avoid collisions.
 *
 * <p>Batch saving groups multiple images into a single {@code docker save} call,
 * which deduplicates shared layers (e.g., all services sharing {@code eclipse-temurin:21-jre-alpine}).
 * This can reduce total I/O by 30-40% for services with a common base image.
 *
 * <p>A deduplication set tracks already-loaded images to avoid redundant loads
 * across test classes sharing the same K3s cluster singleton.
 */
public final class K3sImageLoader {

    private static final Logger log = LoggerFactory.getLogger(K3sImageLoader.class);

    /** Maximum parallel image load threads. Bounded to avoid saturating Docker daemon I/O. */
    private static final int MAX_THREADS = 4;

    /**
     * Threshold for batch saving: if the image list has this many or more images,
     * use a single {@code docker save} call. Below this threshold, parallel individual
     * saves are faster because they can overlap.
     */
    private static final int BATCH_SAVE_THRESHOLD = 3;

    /** Tracks images already loaded into the current K3s cluster to avoid redundant loads. */
    private static final Set<String> loadedImages = new HashSet<>();

    private K3sImageLoader() {
    }

    /**
     * Loads a single Docker image into K3s.
     *
     * @param container the K3s container to load the image into
     * @param imageName the Docker image name (e.g., {@code "mysql:8.4"})
     * @throws Exception if saving, copying, or importing the image fails
     */
    public static synchronized void loadImage(K3sContainer container, String imageName) throws Exception {
        loadImages(container, List.of(imageName));
    }

    /**
     * Loads multiple Docker images into K3s in parallel.
     *
     * <p>Images already loaded (tracked by the deduplication set) are skipped.
     * When {@value #BATCH_SAVE_THRESHOLD} or more new images need loading, they are
     * saved in a single {@code docker save} call to deduplicate shared layers.
     * Below that threshold, individual images are saved in parallel threads.
     *
     * @param container the K3s container to load images into
     * @param imageNames the list of Docker image names to load
     * @throws Exception if any image fails to load
     */
    public static synchronized void loadImages(K3sContainer container, List<String> imageNames)
            throws Exception {
        List<String> toLoad = imageNames.stream()
                .filter(name -> !loadedImages.contains(name))
                .toList();

        if (toLoad.isEmpty()) {
            log.debug("All {} images already loaded", imageNames.size());
            return;
        }

        log.info("Loading {} images into K3s | total={} | alreadyLoaded={}",
                toLoad.size(), imageNames.size(), imageNames.size() - toLoad.size());

        long startMs = System.currentTimeMillis();

        if (toLoad.size() >= BATCH_SAVE_THRESHOLD) {
            batchLoadImages(container, toLoad);
        } else {
            parallelLoadImages(container, toLoad);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("All images loaded | count={} | elapsed={}ms", toLoad.size(), elapsedMs);
    }

    /**
     * Resets the deduplication set. Called when the K3s cluster is recreated.
     */
    static synchronized void resetLoadedImages() {
        loadedImages.clear();
    }

    /**
     * Saves all images in a single {@code docker save} call (shared layers stored once),
     * then copies the combined TAR into K3s and imports it.
     */
    private static void batchLoadImages(K3sContainer container, List<String> images)
            throws Exception {
        log.info("Batch saving {} images in single docker save", images.size());

        Path tempTar = Files.createTempFile("k3s-batch-", ".tar");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("docker");
            cmd.add("save");
            cmd.add("--platform");
            cmd.add("linux/amd64");
            cmd.add("-o");
            cmd.add(tempTar.toString());
            cmd.addAll(images);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Batch docker save failed | images=" + images);
            }

            long tarSizeMb = Files.size(tempTar) / (1024 * 1024);
            log.info("Batch TAR created | size={}MB | images={}", tarSizeMb, images.size());

            String containerPath = "/tmp/batch-images.tar";
            container.copyFileToContainer(MountableFile.forHostPath(tempTar), containerPath);

            var result = container.execInContainer(
                    "ctr", "--namespace", "k8s.io", "images", "import", containerPath);
            if (result.getExitCode() != 0) {
                log.warn("ctr images import stderr: {}", result.getStderr());
                throw new RuntimeException("Batch ctr import failed: " + result.getStderr());
            }

            images.forEach(loadedImages::add);
            log.info("Batch import complete | images={}", images);
        } finally {
            Files.deleteIfExists(tempTar);
        }
    }

    /**
     * Loads images in parallel using individual {@code docker save} calls per image.
     * Each thread uses a unique container-side TAR path to avoid collisions.
     */
    private static void parallelLoadImages(K3sContainer container, List<String> images)
            throws Exception {
        int threadCount = Math.min(images.size(), MAX_THREADS);
        log.info("Parallel loading {} images with {} threads", images.size(), threadCount);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();
            for (String imageName : images) {
                futures.add(executor.submit(() -> {
                    try {
                        loadSingleImage(container, imageName);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load image: " + imageName, e);
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        }
    }

    /**
     * Loads a single image via {@code docker save → copyFileToContainer → ctr import}.
     * Uses a unique container-side path derived from the image name hash.
     */
    private static void loadSingleImage(K3sContainer container, String imageName)
            throws Exception {
        log.info("Loading image into K3s | image={}", imageName);

        Path tempTar = Files.createTempFile("k3s-image-", ".tar");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "save", "--platform", "linux/amd64",
                    "-o", tempTar.toString(), imageName);
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Failed to save Docker image: " + imageName);
            }

            // Unique container path per image to allow parallel copies
            String containerPath = "/tmp/image-" + Math.abs(imageName.hashCode()) + ".tar";
            container.copyFileToContainer(MountableFile.forHostPath(tempTar), containerPath);

            var result = container.execInContainer(
                    "ctr", "--namespace", "k8s.io", "images", "import", containerPath);
            if (result.getExitCode() != 0) {
                log.warn("ctr images import stderr: {}", result.getStderr());
                throw new RuntimeException("Failed to import image into K3s: " + result.getStderr());
            }

            synchronized (loadedImages) {
                loadedImages.add(imageName);
            }
            log.info("Image loaded into K3s | image={}", imageName);
        } finally {
            Files.deleteIfExists(tempTar);
        }
    }
}
