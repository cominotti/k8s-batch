// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a new account.
 *
 * @param customerId owning customer's surrogate ID
 * @param accountId  unique business key (UUID string)
 * @param currency   ISO 4217 currency code (3 characters)
 */
public record CreateAccountRequest(
        @NotNull Long customerId,
        @NotBlank String accountId,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}
