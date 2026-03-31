// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.repository;

import com.cominotti.k8sbatch.crud.adapters.persistingaccounts.jpa.AccountRepository;
import com.cominotti.k8sbatch.crud.adapters.persistingcustomers.jpa.CustomerRepository;
import com.cominotti.k8sbatch.crud.domain.Account;
import com.cominotti.k8sbatch.crud.domain.AccountStatus;
import com.cominotti.k8sbatch.crud.domain.Customer;
import com.cominotti.k8sbatch.crud.it.AbstractCrudSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import static com.cominotti.k8sbatch.crud.it.fixture.TestAccounts.anAccount;
import static com.cominotti.k8sbatch.crud.it.fixture.TestCustomers.aCustomer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for {@link AccountRepository} using a real MySQL database.
 */
class AccountRepositoryIT extends AbstractCrudSliceTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TestEntityManager tem;

    @Test
    void shouldSaveAccountWithCustomer() {
        Customer customer = customerRepository.save(aCustomer());
        Account account = anAccount("acc-001", customer, "USD");
        Account saved = accountRepository.save(account);
        tem.flush();
        tem.clear();

        Account loaded = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getAccountId()).isEqualTo("acc-001");
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(loaded.getCustomer().getId()).isEqualTo(customer.getId());
    }

    @Test
    void shouldFindAccountByBusinessKey() {
        Customer customer = customerRepository.save(aCustomer());
        accountRepository.save(anAccount("business-key-123", customer, "EUR"));
        tem.flush();
        tem.clear();

        assertThat(accountRepository.findByAccountId("business-key-123")).isPresent()
                .get().satisfies(a -> assertThat(a.getCurrency()).isEqualTo("EUR"));
    }

    @Test
    void shouldFindAccountsByCustomerId() {
        Customer customer = customerRepository.save(aCustomer());
        accountRepository.save(anAccount("acc-a", customer, "USD"));
        accountRepository.save(anAccount("acc-b", customer, "EUR"));
        tem.flush();
        tem.clear();

        assertThat(accountRepository.findByCustomerId(customer.getId())).hasSize(2);
    }

    @Test
    void shouldReturnEmptyForNonExistentAccountId() {
        assertThat(accountRepository.findByAccountId("nonexistent")).isEmpty();
    }
}
