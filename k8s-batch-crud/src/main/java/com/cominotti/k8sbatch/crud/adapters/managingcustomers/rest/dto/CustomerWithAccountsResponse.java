// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto;

import com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.AccountResponse;
import com.cominotti.k8sbatch.crud.domain.Customer;
import com.cominotti.k8sbatch.crud.domain.CustomerStatus;

import java.time.Instant;
import java.util.List;

/**
 * Response body for a customer with nested account summaries. Uses the EntityGraph query
 * to avoid N+1 when loading accounts.
 *
 * @param id        surrogate ID
 * @param name      display name
 * @param email     unique email
 * @param status    lifecycle status
 * @param accounts  the customer's accounts
 * @param createdAt creation timestamp
 * @param updatedAt last modification timestamp
 */
public record CustomerWithAccountsResponse(
        Long id,
        String name,
        String email,
        CustomerStatus status,
        List<AccountResponse> accounts,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Maps a JPA entity (with accounts eagerly loaded) to a response DTO.
     *
     * @param customer the entity with accounts
     * @return the DTO
     */
    public static CustomerWithAccountsResponse from(Customer customer) {
        List<AccountResponse> accountDtos = customer.getAccounts().stream()
                .map(AccountResponse::from)
                .toList();
        return new CustomerWithAccountsResponse(
                customer.getId(), customer.getName(), customer.getEmail(),
                customer.getStatus(), accountDtos,
                customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
