// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto;

import com.cominotti.k8sbatch.crud.domain.Account;
import com.cominotti.k8sbatch.crud.domain.AccountStatus;

import java.time.Instant;

/**
 * Response body for an account.
 *
 * @param id         surrogate ID
 * @param accountId  natural business key (UUID string)
 * @param customerId owning customer's surrogate ID
 * @param currency   ISO 4217 currency code
 * @param status     lifecycle status
 * @param createdAt  creation timestamp
 * @param updatedAt  last modification timestamp
 */
public record AccountResponse(
        Long id,
        String accountId,
        Long customerId,
        String currency,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Maps a JPA entity to a response DTO.
     *
     * @param account the entity
     * @return the DTO
     */
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(), account.getAccountId(), account.getCustomer().getId(),
                account.getCurrency(), account.getStatus(),
                account.getCreatedAt(), account.getUpdatedAt());
    }
}
