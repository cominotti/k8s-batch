// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.persistingaccounts.jpa;

import com.cominotti.k8sbatch.crud.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Account} entities.
 *
 * <p>Supports lookup by the natural business key ({@code accountId}) for correlation with
 * the batch pipeline's {@code enriched_transactions.account_id}.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Finds an account by its natural business key (UUID string).
     *
     * @param accountId the business key to search for
     * @return the account if found
     */
    Optional<Account> findByAccountId(String accountId);

    /**
     * Lists all accounts belonging to a given customer.
     *
     * @param customerId the owning customer's surrogate ID
     * @return accounts for this customer (may be empty)
     */
    List<Account> findByCustomerId(Long customerId);

    /**
     * Checks whether an account with the given business key already exists.
     *
     * @param accountId the business key to check
     * @return {@code true} if an account with this ID exists
     */
    boolean existsByAccountId(String accountId);

    /**
     * Deletes all accounts belonging to a given customer in a single bulk DELETE statement.
     * Used by customer deletion to remove dependent accounts without loading entities into memory.
     *
     * @param customerId the owning customer's surrogate ID
     */
    void deleteByCustomerId(Long customerId);
}
