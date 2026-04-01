// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.repository;

import com.cominotti.k8sbatch.crud.adapters.persistingcustomers.jpa.CustomerRepository;
import com.cominotti.k8sbatch.crud.domain.Customer;
import com.cominotti.k8sbatch.crud.domain.CustomerStatus;
import com.cominotti.k8sbatch.crud.it.AbstractCrudSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import static com.cominotti.k8sbatch.crud.it.fixture.TestAccounts.anAccount;
import static com.cominotti.k8sbatch.crud.it.fixture.TestCustomers.aCustomer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for {@link CustomerRepository} using a real MySQL database.
 */
class CustomerRepositoryIT extends AbstractCrudSliceTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager tem;

    @Test
    void shouldSaveAndFindCustomerById() {
        Customer saved = customerRepository.save(aCustomer());
        tem.flush();
        tem.clear();

        assertThat(customerRepository.findById(saved.getId())).isPresent()
                .get().satisfies(c -> {
                    assertThat(c.getName()).isEqualTo("John Doe");
                    assertThat(c.getEmail()).isEqualTo("john@example.com");
                    assertThat(c.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
                    assertThat(c.getCreatedAt()).isNotNull();
                    assertThat(c.getUpdatedAt()).isNotNull();
                });
    }

    @Test
    void shouldFindCustomerByEmail() {
        customerRepository.save(aCustomer("Alice", "alice@example.com"));
        tem.flush();
        tem.clear();

        assertThat(customerRepository.findByEmail("alice@example.com")).isPresent()
                .get().satisfies(c -> assertThat(c.getName()).isEqualTo("Alice"));
    }

    @Test
    void shouldReturnEmptyForNonExistentEmail() {
        assertThat(customerRepository.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void shouldCheckEmailExistence() {
        customerRepository.save(aCustomer());
        tem.flush();

        assertThat(customerRepository.existsByEmail("john@example.com")).isTrue();
        assertThat(customerRepository.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void shouldLoadCustomerWithAccountsViaEntityGraph() {
        Customer customer = aCustomer();
        customer.addAccount(anAccount(customer));
        customer.addAccount(anAccount("acc-002", customer, "EUR"));
        customerRepository.save(customer);
        tem.flush();
        tem.clear();

        Customer loaded = customerRepository.findWithAccountsById(customer.getId()).orElseThrow();
        assertThat(loaded.getAccounts()).hasSize(2);
    }

    @Test
    void shouldIncrementVersionOnUpdate() {
        Customer customer = customerRepository.save(aCustomer());
        tem.flush();
        assertThat(customer.getVersion()).isZero();

        customer.rename("Updated Name");
        tem.flush();
        assertThat(customer.getVersion()).isEqualTo(1);
    }
}
