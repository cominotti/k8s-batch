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

/**
 * HTTP client for the Customer/Account CRUD REST API.
 *
 * <p>Uses {@link java.net.http.HttpClient} (no Spring dependency) to match the
 * {@link BatchAppClient} pattern. Provides CRUD operations for customers and accounts,
 * plus a health check endpoint.
 */
public final class CrudAppClient {

    private static final Logger log = LoggerFactory.getLogger(CrudAppClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates an HTTP client for the CRUD REST API.
     *
     * @param localPort the locally-forwarded port number from
     *                  {@link com.cominotti.k8sbatch.e2e.cluster.PortForwardManager#forwardToCrud(int)}
     */
    public CrudAppClient(int localPort) {
        this.baseUrl = "http://localhost:" + localPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Creates a customer via POST /api/customers.
     *
     * @param name  customer name
     * @param email customer email
     * @return parsed JSON response
     * @throws Exception if the request fails
     */
    public JsonNode createCustomer(String name, String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", name, "email", email));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/customers"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("POST /api/customers | status={} | body={}", response.statusCode(), response.body());
        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to create customer: " + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Gets a customer by ID via GET /api/customers/{id}.
     *
     * @param id customer surrogate ID
     * @return parsed JSON response, or null if 404
     * @throws Exception if the request fails
     */
    public JsonNode getCustomer(long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/customers/" + id))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Updates a customer via PUT /api/customers/{id}.
     *
     * @param id     customer surrogate ID
     * @param name   new name
     * @param status new status (ACTIVE, SUSPENDED, CLOSED)
     * @return parsed JSON response
     * @throws Exception if the request fails
     */
    public JsonNode updateCustomer(long id, String name, String status) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", name, "status", status));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/customers/" + id))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("PUT /api/customers/{} | status={}", id, response.statusCode());
        return objectMapper.readTree(response.body());
    }

    /**
     * Deletes a customer via DELETE /api/customers/{id}.
     *
     * @param id customer surrogate ID
     * @return HTTP status code
     * @throws Exception if the request fails
     */
    public int deleteCustomer(long id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/customers/" + id))
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("DELETE /api/customers/{} | status={}", id, response.statusCode());
        return response.statusCode();
    }

    /**
     * Creates an account via POST /api/accounts.
     *
     * @param customerId owning customer ID
     * @param accountId  business key (UUID string)
     * @param currency   ISO 4217 currency code
     * @return parsed JSON response
     * @throws Exception if the request fails
     */
    public JsonNode createAccount(long customerId, String accountId, String currency) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("customerId", customerId, "accountId", accountId, "currency", currency));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/accounts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("POST /api/accounts | status={} | body={}", response.statusCode(), response.body());
        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to create account: " + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Gets the actuator health endpoint.
     *
     * @return health response body
     * @throws Exception if the request fails
     */
    public String getHealth() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/actuator/health"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
