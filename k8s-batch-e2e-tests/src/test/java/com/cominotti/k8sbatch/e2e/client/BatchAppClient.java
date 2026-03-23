// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * HTTP client for the batch Job REST API.
 * Uses java.net.http.HttpClient (no Spring dependency).
 */
public final class BatchAppClient {

    private static final Logger log = LoggerFactory.getLogger(BatchAppClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates an HTTP client for the batch job REST API.
     * Uses {@link java.net.http.HttpClient} with a 10-second connect timeout.
     * No Spring dependencies — pure JDK HTTP client.
     *
     * @param baseUrl the base URL including scheme and port (e.g., "http://localhost:8080")
     */
    public BatchAppClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Convenience constructor that builds a localhost URL from the given port.
     * Typically used with the port returned by
     * {@link com.cominotti.k8sbatch.e2e.cluster.PortForwardManager#forwardToApp()}.
     *
     * @param localPort the locally-forwarded port number
     */
    public BatchAppClient(int localPort) {
        this("http://localhost:" + localPort);
    }

    /**
     * Launches a batch job.
     *
     * @param jobName    the Spring Batch job bean name (e.g., "fileRangeEtlJob")
     * @param parameters job parameters as key-value pairs passed to the job
     * @return the parsed response containing executionId and initial status
     * @throws Exception if the HTTP request or JSON parsing fails
     */
    public JobResponse launchJob(String jobName, Map<String, String> parameters) throws Exception {
        String body = objectMapper.writeValueAsString(parameters);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/jobs/" + jobName))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("POST /api/jobs/{} | status={} | body={}", jobName, response.statusCode(), response.body());

        return parseJobResponse(response.body());
    }

    /**
     * Gets the status of a job execution.
     *
     * @param jobName     the job bean name
     * @param executionId the execution ID returned by {@link #launchJob}
     * @return the current execution status, or {@code null} if the execution is not found (HTTP 404)
     * @throws Exception on HTTP or parse errors
     */
    public JobResponse getExecution(String jobName, long executionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/jobs/" + jobName + "/executions/" + executionId))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        return parseJobResponse(response.body());
    }

    /**
     * Launches a job and polls until it reaches COMPLETED or FAILED.
     *
     * @param jobName    the job bean name
     * @param parameters job parameters
     * @param timeout    maximum time to wait for completion
     * @return the final execution response with COMPLETED or FAILED status
     * @throws org.awaitility.core.ConditionTimeoutException if the job does not complete within the timeout
     */
    public JobResponse launchJobAndWaitForCompletion(String jobName, Map<String, String> parameters,
                                                      Duration timeout) throws Exception {
        JobResponse launch = launchJob(jobName, parameters);
        long executionId = launch.executionId();

        log.info("Waiting for job completion | jobName={} | executionId={} | timeout={}",
                jobName, executionId, timeout);

        // Single-element array used to capture the latest poll result inside the Awaitility lambda —
        // lambdas require effectively-final variables, so a mutable container is needed
        final JobResponse[] result = new JobResponse[1];
        await().atMost(timeout)
                .pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    JobResponse status = getExecution(jobName, executionId);
                    result[0] = status;
                    if (status != null) {
                        log.debug("Job status poll | executionId={} | status={}", executionId, status.status());
                    }
                    assert status != null;
                    assert "COMPLETED".equals(status.status()) || "FAILED".equals(status.status());
                });

        log.info("Job finished | jobName={} | executionId={} | status={} | exitCode={}",
                jobName, executionId, result[0].status(), result[0].exitCode());
        return result[0];
    }

    /**
     * Checks /actuator/health.
     *
     * @return the raw JSON response body from /actuator/health
     * @throws Exception on HTTP errors
     */
    public String getHealth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/actuator/health"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * Parses a JSON response string from the batch job REST API into a {@link JobResponse} record.
     * Extracts executionId, jobName, status, and optional exitCode/exitDescription fields.
     *
     * @param json the raw JSON response body
     * @return the parsed response
     * @throws Exception if JSON parsing fails
     */
    private JobResponse parseJobResponse(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return new JobResponse(
                node.get("executionId").asLong(),
                node.get("jobName").asText(),
                node.get("status").asText(),
                node.has("exitCode") ? node.get("exitCode").asText() : "",
                node.has("exitDescription") ? node.get("exitDescription").asText() : "");
    }

    /** Parsed JSON response from the batch job REST API. */
    public record JobResponse(long executionId, String jobName, String status,
                                String exitCode, String exitDescription) {
    }
}
