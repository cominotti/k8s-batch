// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain.port;

import com.cominotti.k8sbatch.crud.domain.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.Optional;

/**
 * Driven port for Customer persistence. The domain depends on this interface; the JPA adapter
 * ({@code CustomerRepository}) extends it.
 *
 * <p>Extends {@link CrudRepository} and {@link PagingAndSortingRepository} to inherit standard
 * CRUD and paging signatures from a single source — this avoids ambiguous method resolution
 * when the JPA adapter also extends {@code JpaRepository} (both share these as common ancestors
 * via diamond inheritance).
 */
public interface CustomerPersistencePort
        extends CrudRepository<Customer, Long>, PagingAndSortingRepository<Customer, Long> {

    /**
     * Finds a customer by their unique email (natural business key).
     *
     * @param email the email address
     * @return the customer if found
     */
    Optional<Customer> findByEmail(String email);

    /**
     * Loads a customer with their accounts eagerly fetched.
     *
     * @param id the customer's surrogate ID
     * @return the customer with accounts loaded
     */
    Optional<Customer> findWithAccountsById(Long id);
}
