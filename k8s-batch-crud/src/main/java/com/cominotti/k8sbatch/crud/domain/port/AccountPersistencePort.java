// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain.port;

import com.cominotti.k8sbatch.crud.domain.Account;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for Account persistence. The domain depends on this interface; the JPA adapter
 * ({@code AccountRepository}) extends it.
 *
 * <p>Extends {@link CrudRepository} to inherit standard CRUD signatures ({@code save},
 * {@code findById}, {@code existsById}, {@code deleteById}) from a single source — this avoids
 * ambiguous method resolution when the JPA adapter also extends {@code JpaRepository} (both
 * share {@code CrudRepository} as a common ancestor via diamond inheritance).
 */
public interface AccountPersistencePort extends CrudRepository<Account, Long> {

    /**
     * Finds an account by its natural business key (UUID string).
     *
     * @param accountId the business key
     * @return the account if found
     */
    Optional<Account> findByAccountId(String accountId);

    /**
     * Lists all accounts belonging to a given customer.
     *
     * @param customerId the customer's surrogate ID
     * @return accounts for this customer (may be empty)
     */
    List<Account> findByCustomerId(Long customerId);

    /**
     * Deletes all accounts belonging to a given customer in bulk.
     *
     * @param customerId the customer's surrogate ID
     */
    void deleteByCustomerId(Long customerId);
}
