// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.persistingaccounts.jpa;

import com.cominotti.k8sbatch.crud.domain.Account;
import com.cominotti.k8sbatch.crud.domain.port.AccountPersistencePort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA adapter for {@link Account} persistence. Implements the domain's
 * {@link AccountPersistencePort} — Spring Data generates the implementation at runtime.
 *
 * <p>Domain-specific query methods ({@code findByAccountId}, {@code findByCustomerId},
 * {@code deleteByCustomerId}) are inherited from the port interface. This adapter only adds
 * methods not on the port.
 */
public interface AccountRepository extends JpaRepository<Account, Long>, AccountPersistencePort {

    /**
     * Checks whether an account with the given business key already exists.
     *
     * @param accountId the business key to check
     * @return {@code true} if an account with this ID exists
     */
    boolean existsByAccountId(String accountId);
}
