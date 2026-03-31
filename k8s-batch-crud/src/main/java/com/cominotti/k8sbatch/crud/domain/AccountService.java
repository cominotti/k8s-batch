// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain;

import com.cominotti.k8sbatch.crud.adapters.persistingaccounts.jpa.AccountRepository;
import com.cominotti.k8sbatch.crud.adapters.persistingcustomers.jpa.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Application service for Account lifecycle operations.
 *
 * <p>Accounts are separate aggregates from Customers because they must be independently queryable
 * by {@code accountId} for correlation with the batch pipeline's {@code enriched_transactions}.
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    /**
     * Creates the service with the required repositories.
     *
     * @param accountRepository  persistence port for account entities
     * @param customerRepository persistence port for customer entities (validates ownership)
     */
    public AccountService(AccountRepository accountRepository, CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    /**
     * Creates a new account for the given customer.
     *
     * @param customerId the owning customer's ID
     * @param accountId  unique business key (UUID string, same as enriched_transactions.account_id)
     * @param currency   ISO 4217 currency code
     * @return the persisted account
     * @throws EntityNotFoundException         if the customer does not exist
     * @throws DataIntegrityViolationException if the accountId is already taken
     */
    @Transactional
    public Account createAccount(Long customerId, String accountId, String currency) {
        log.info("Creating account | customerId={} | accountId={} | currency={}", customerId, accountId, currency);
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new EntityNotFoundException("Customer", customerId));
        Account account = new Account(accountId, customer, currency);
        return accountRepository.save(account);
    }

    /**
     * Finds an account by surrogate ID.
     *
     * @param id the account surrogate ID
     * @return the account if found
     */
    public Optional<Account> findById(Long id) {
        return accountRepository.findById(id);
    }

    /**
     * Finds an account by its natural business key (UUID string).
     *
     * @param accountId the business key
     * @return the account if found
     */
    public Optional<Account> findByAccountId(String accountId) {
        return accountRepository.findByAccountId(accountId);
    }

    /**
     * Lists all accounts belonging to a given customer.
     *
     * @param customerId the customer's surrogate ID
     * @return accounts for this customer
     */
    public List<Account> findByCustomerId(Long customerId) {
        return accountRepository.findByCustomerId(customerId);
    }

    /**
     * Updates the status of an existing account.
     *
     * @param id     the account surrogate ID
     * @param status the new status
     * @return the updated account
     * @throws EntityNotFoundException if the account does not exist
     */
    @Transactional
    public Account updateAccountStatus(Long id, AccountStatus status) {
        log.info("Updating account status | id={} | status={}", id, status);
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Account", id));
        account.setStatus(status);
        return account;
    }

    /**
     * Deletes an account by ID.
     *
     * @param id the account surrogate ID
     * @throws EntityNotFoundException if the account does not exist
     */
    @Transactional
    public void deleteAccount(Long id) {
        log.info("Deleting account | id={}", id);
        if (!accountRepository.existsById(id)) {
            throw new EntityNotFoundException("Account", id);
        }
        accountRepository.deleteById(id);
    }
}
