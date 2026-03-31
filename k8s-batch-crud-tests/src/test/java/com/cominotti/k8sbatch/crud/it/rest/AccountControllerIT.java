// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.rest;

import com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.AccountResponse;
import com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.CreateAccountRequest;
import com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.UpdateAccountStatusRequest;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CreateCustomerRequest;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CustomerResponse;
import com.cominotti.k8sbatch.crud.domain.AccountStatus;
import com.cominotti.k8sbatch.crud.it.AbstractCrudIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST round-trip tests for the Account API endpoints.
 */
class AccountControllerIT extends AbstractCrudIntegrationTest {

    @Test
    void shouldCreateAccountAndReturnCreated() {
        CustomerResponse customer = createCustomer("Alice", "alice@test.com");

        AccountResponse response = restClient().post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateAccountRequest(customer.id(), "acc-001", "USD"))
                .retrieve()
                .body(AccountResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.accountId()).isEqualTo("acc-001");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.customerId()).isEqualTo(customer.id());
    }

    @Test
    void shouldFindAccountByBusinessKey() {
        CustomerResponse customer = createCustomer("Bob", "bob@test.com");
        createAccount(customer.id(), "biz-key-123", "EUR");

        AccountResponse found = restClient().get()
                .uri("/api/accounts?accountId={accountId}", "biz-key-123")
                .retrieve()
                .body(AccountResponse.class);

        assertThat(found).isNotNull();
        assertThat(found.accountId()).isEqualTo("biz-key-123");
    }

    @Test
    void shouldUpdateAccountStatus() {
        CustomerResponse customer = createCustomer("Charlie", "charlie@test.com");
        AccountResponse created = createAccount(customer.id(), "acc-freeze", "USD");

        AccountResponse updated = restClient().put()
                .uri("/api/accounts/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateAccountStatusRequest(AccountStatus.FROZEN))
                .retrieve()
                .body(AccountResponse.class);

        assertThat(updated.status()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void shouldDeleteAccount() {
        CustomerResponse customer = createCustomer("Dave", "dave@test.com");
        AccountResponse created = createAccount(customer.id(), "acc-delete", "USD");

        restClient().delete()
                .uri("/api/accounts/{id}", created.id())
                .retrieve()
                .toBodilessEntity();
    }

    private CustomerResponse createCustomer(String name, String email) {
        return restClient().post()
                .uri("/api/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateCustomerRequest(name, email))
                .retrieve()
                .body(CustomerResponse.class);
    }

    private AccountResponse createAccount(Long customerId, String accountId, String currency) {
        return restClient().post()
                .uri("/api/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateAccountRequest(customerId, accountId, currency))
                .retrieve()
                .body(AccountResponse.class);
    }
}
