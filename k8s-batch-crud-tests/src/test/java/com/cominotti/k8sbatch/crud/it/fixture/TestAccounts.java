// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.fixture;

import com.cominotti.k8sbatch.crud.domain.Account;
import com.cominotti.k8sbatch.crud.domain.Customer;

import java.util.UUID;

/**
 * Factory methods for creating test {@link Account} instances.
 */
public final class TestAccounts {

    /**
     * Creates a test account with a random UUID as the business key.
     *
     * @param owner the owning customer
     * @return a new USD account
     */
    public static Account anAccount(Customer owner) {
        return new Account(UUID.randomUUID().toString(), owner, "USD");
    }

    /**
     * Creates a test account with the specified business key and currency.
     *
     * @param accountId the business key
     * @param owner     the owning customer
     * @param currency  ISO 4217 currency code
     * @return a new account
     */
    public static Account anAccount(String accountId, Customer owner, String currency) {
        return new Account(accountId, owner, currency);
    }

    private TestAccounts() {
    }
}
