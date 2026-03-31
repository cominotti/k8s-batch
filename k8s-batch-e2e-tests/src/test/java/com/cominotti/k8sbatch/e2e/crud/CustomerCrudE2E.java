// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.crud;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.E2EProfile;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for the Customer/Account CRUD microservice deployed into K3s.
 *
 * <p>Validates the full deployment stack: Liquibase schema migration, JPA entity mapping,
 * Spring Data repositories, REST controllers, and gateway proxy — all running in Kubernetes
 * with real MySQL. Uses the standalone profile (no Kafka needed for CRUD operations).
 */
@E2EProfile("e2e-standalone.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerCrudE2E extends AbstractE2ETest {

    @Override
    protected String valuesFile() {
        return "e2e-standalone.yaml";
    }

    @Override
    protected boolean requiresCrud() {
        return true;
    }

    /**
     * Verifies that the CRUD service pod is healthy and the actuator reports UP.
     * This confirms that Liquibase ran, Hibernate validated the schema, and the
     * Spring context started successfully.
     */
    @Test
    @Order(1)
    void crudServiceShouldBeHealthy() throws Exception {
        String health = crudClient.getHealth();
        assertThat(health).contains("\"status\":\"UP\"");
    }

    /**
     * Full customer lifecycle: create, read, update, delete.
     */
    @Test
    @Order(2)
    void shouldPerformCustomerCrudLifecycle() throws Exception {
        // Create
        JsonNode created = crudClient.createCustomer("E2E Test User", "e2e@example.com");
        assertThat(created.get("id")).isNotNull();
        assertThat(created.get("name").asText()).isEqualTo("E2E Test User");
        assertThat(created.get("email").asText()).isEqualTo("e2e@example.com");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

        long customerId = created.get("id").asLong();

        // Read
        JsonNode fetched = crudClient.getCustomer(customerId);
        assertThat(fetched).isNotNull();
        assertThat(fetched.get("email").asText()).isEqualTo("e2e@example.com");

        // Update
        JsonNode updated = crudClient.updateCustomer(customerId, "Updated Name", "SUSPENDED");
        assertThat(updated.get("name").asText()).isEqualTo("Updated Name");
        assertThat(updated.get("status").asText()).isEqualTo("SUSPENDED");

        // Delete
        int deleteStatus = crudClient.deleteCustomer(customerId);
        assertThat(deleteStatus).isEqualTo(204);

        // Verify gone
        JsonNode gone = crudClient.getCustomer(customerId);
        assertThat(gone).isNull();
    }

    /**
     * Creates a customer with an account, verifying the full entity graph deployment.
     */
    @Test
    @Order(3)
    void shouldCreateCustomerWithAccount() throws Exception {
        String accountId = UUID.randomUUID().toString();

        JsonNode customer = crudClient.createCustomer("Account Owner", "owner-" + accountId + "@example.com");
        long customerId = customer.get("id").asLong();

        JsonNode account = crudClient.createAccount(customerId, accountId, "USD");
        assertThat(account.get("accountId").asText()).isEqualTo(accountId);
        assertThat(account.get("currency").asText()).isEqualTo("USD");
        assertThat(account.get("customerId").asLong()).isEqualTo(customerId);
    }

    /**
     * Verifies that the MySQL tables created by Liquibase are accessible and that
     * the CRUD service can write to them (end-to-end data persistence verification).
     */
    @Test
    @Order(4)
    void shouldPersistDataToMysql() throws Exception {
        assertThat(mysqlVerifier.customersTableExists()).isTrue();
        assertThat(mysqlVerifier.accountsTableExists()).isTrue();
    }
}
