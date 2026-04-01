// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.persistingcustomers.jpa;

import com.cominotti.k8sbatch.crud.domain.Customer;
import com.cominotti.k8sbatch.crud.domain.port.CustomerPersistencePort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA adapter for {@link Customer} persistence. Implements the domain's
 * {@link CustomerPersistencePort} — Spring Data generates the implementation at runtime.
 *
 * <p>Domain-specific query methods ({@code findByEmail}, {@code findWithAccountsById}) are
 * inherited from the port interface. This adapter re-declares {@code findWithAccountsById}
 * to add the {@code @EntityGraph} annotation, and adds methods not on the port.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long>, CustomerPersistencePort {

    /**
     * Checks whether a customer with the given email already exists.
     *
     * @param email the email to check
     * @return {@code true} if a customer with this email exists
     */
    boolean existsByEmail(String email);

    /**
     * Loads a customer with their accounts eagerly fetched in a single query (JOIN FETCH).
     * Avoids the N+1 problem when accessing the {@code accounts} collection.
     *
     * @param id the customer's surrogate ID
     * @return the customer with accounts loaded
     */
    @EntityGraph(attributePaths = "accounts")
    @Override
    Optional<Customer> findWithAccountsById(Long id);
}
