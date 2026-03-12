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

    public BatchAppClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public BatchAppClient(int localPort) {
        this("http://localhost:" + localPort);
    }

    /**
     * Launches a batch job.
     *
     * @return the parsed response containing executionId and status
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
     */
    public JobResponse launchJobAndWaitForCompletion(String jobName, Map<String, String> parameters,
                                                      Duration timeout) throws Exception {
        JobResponse launch = launchJob(jobName, parameters);
        long executionId = launch.executionId();

        log.info("Waiting for job completion | jobName={} | executionId={} | timeout={}",
                jobName, executionId, timeout);

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
