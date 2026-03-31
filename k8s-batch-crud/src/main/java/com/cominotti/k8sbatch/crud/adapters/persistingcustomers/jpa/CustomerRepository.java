// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.persistingcustomers.jpa;

import com.cominotti.k8sbatch.crud.domain.Customer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Customer} entities.
 *
 * <p>The repository interface itself is the hexagonal persistence port — Spring Data generates
 * the implementation at runtime. No separate port interface is needed.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Finds a customer by their unique email address (natural business key).
     *
     * @param email the email to search for
     * @return the customer if found
     */
    Optional<Customer> findByEmail(String email);

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
    Optional<Customer> findWithAccountsById(Long id);
}
