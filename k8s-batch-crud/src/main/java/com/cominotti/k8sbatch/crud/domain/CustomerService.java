// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain;

import com.cominotti.k8sbatch.crud.domain.port.AccountPersistencePort;
import com.cominotti.k8sbatch.crud.domain.port.CustomerPersistencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Application service for Customer lifecycle operations.
 *
 * <p>Read-only by default ({@code @Transactional(readOnly = true)} at class level). Write methods
 * override with {@code @Transactional} to enable read-write transactions and Hibernate dirty
 * checking.
 *
 * <p><strong>CQS note:</strong> Write methods (create, update, delete) return the mutated entity
 * as a pragmatic relaxation of Command-Query Separation — this avoids an extra round-trip to
 * query the entity after mutation and is the standard Spring convention.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerPersistencePort customerRepository;
    private final AccountPersistencePort accountRepository;

    /**
     * Creates the service with the required persistence ports.
     *
     * @param customerRepository persistence port for customer entities
     * @param accountRepository  persistence port for account entities (needed for cascade delete)
     */
    public CustomerService(CustomerPersistencePort customerRepository, AccountPersistencePort accountRepository) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Creates a new customer with the given name and email.
     *
     * @param name  display name
     * @param email unique email address
     * @return the persisted customer
     * @throws DataIntegrityViolationException if the email is already taken
     */
    @Transactional
    public Customer createCustomer(String name, String email) {
        log.info("Creating customer | email={}", email);
        Customer customer = new Customer(name, email);
        return customerRepository.save(customer);
    }

    /**
     * Finds a customer by surrogate ID.
     *
     * @param id the customer ID
     * @return the customer if found
     */
    public Optional<Customer> findById(Long id) {
        return customerRepository.findById(id);
    }

    /**
     * Finds a customer by their unique email (natural business key).
     *
     * @param email the email to search for
     * @return the customer if found
     */
    public Optional<Customer> findByEmail(String email) {
        return customerRepository.findByEmail(email);
    }

    /**
     * Loads a customer with their accounts eagerly fetched (avoids N+1).
     *
     * @param id the customer ID
     * @return the customer with accounts loaded
     */
    public Optional<Customer> findWithAccounts(Long id) {
        return customerRepository.findWithAccountsById(id);
    }

    /**
     * Returns a page of customers.
     *
     * @param pageable pagination parameters
     * @return page of customers
     */
    public Page<Customer> findAll(Pageable pageable) {
        return customerRepository.findAll(pageable);
    }

    /**
     * Updates the name and status of an existing customer.
     *
     * @param id     the customer ID
     * @param name   new display name
     * @param status new lifecycle status
     * @return the updated customer
     * @throws EntityNotFoundException if the customer does not exist
     */
    @Transactional
    public Customer updateCustomer(Long id, String name, CustomerStatus status) {
        log.info("Updating customer | id={} | status={}", id, status);
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Customer", id));
        customer.rename(name);
        customer.transitionTo(status);
        return customer;
    }

    /**
     * Deletes a customer by ID.
     *
     * @param id the customer ID
     * @throws EntityNotFoundException if the customer does not exist
     */
    @Transactional
    public void deleteCustomer(Long id) {
        log.info("Deleting customer | id={}", id);
        if (!customerRepository.existsById(id)) {
            throw new EntityNotFoundException("Customer", id);
        }
        accountRepository.deleteByCustomerId(id);
        customerRepository.deleteById(id);
    }
}
