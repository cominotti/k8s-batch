// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.rest;

import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CreateCustomerRequest;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CustomerResponse;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CustomerWithAccountsResponse;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.UpdateCustomerRequest;
import com.cominotti.k8sbatch.crud.domain.CustomerStatus;
import com.cominotti.k8sbatch.crud.it.AbstractCrudIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REST round-trip tests for the Customer API endpoints.
 */
class CustomerControllerIT extends AbstractCrudIntegrationTest {

    @Test
    void shouldCreateCustomerAndReturnCreated() {
        CustomerResponse response = restClient().post()
                .uri("/api/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateCustomerRequest("Alice", "alice@example.com"))
                .retrieve()
                .body(CustomerResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Alice");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.status()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void shouldGetCustomerById() {
        CustomerResponse created = createCustomer("Bob", "bob@example.com");

        CustomerResponse fetched = restClient().get()
                .uri("/api/customers/{id}", created.id())
                .retrieve()
                .body(CustomerResponse.class);

        assertThat(fetched).isNotNull();
        assertThat(fetched.email()).isEqualTo("bob@example.com");
    }

    @Test
    void shouldReturnNotFoundForNonExistentCustomer() {
        assertThatThrownBy(() -> restClient().get()
                .uri("/api/customers/{id}", 999999L)
                .retrieve()
                .body(CustomerResponse.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void shouldUpdateCustomer() {
        CustomerResponse created = createCustomer("Charlie", "charlie@example.com");

        CustomerResponse updated = restClient().put()
                .uri("/api/customers/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateCustomerRequest("Charles", CustomerStatus.SUSPENDED))
                .retrieve()
                .body(CustomerResponse.class);

        assertThat(updated).isNotNull();
        assertThat(updated.name()).isEqualTo("Charles");
        assertThat(updated.status()).isEqualTo(CustomerStatus.SUSPENDED);
    }

    @Test
    void shouldDeleteCustomer() {
        CustomerResponse created = createCustomer("Dave", "dave@example.com");

        restClient().delete()
                .uri("/api/customers/{id}", created.id())
                .retrieve()
                .toBodilessEntity();

        assertThatThrownBy(() -> restClient().get()
                .uri("/api/customers/{id}", created.id())
                .retrieve()
                .body(CustomerResponse.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void shouldGetCustomerWithAccounts() {
        CustomerResponse customer = createCustomer("Eve", "eve@example.com");
        String accountId = UUID.randomUUID().toString();

        restClient().post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.CreateAccountRequest(
                        customer.id(), accountId, "USD"))
                .retrieve()
                .toBodilessEntity();

        CustomerWithAccountsResponse response = restClient().get()
                .uri("/api/customers/{id}/accounts", customer.id())
                .retrieve()
                .body(CustomerWithAccountsResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.accounts()).hasSize(1);
        assertThat(response.accounts().getFirst().accountId()).isEqualTo(accountId);
    }

    private CustomerResponse createCustomer(String name, String email) {
        return restClient().post()
                .uri("/api/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateCustomerRequest(name, email))
                .retrieve()
                .body(CustomerResponse.class);
    }
}
