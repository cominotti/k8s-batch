// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto;

import com.cominotti.k8sbatch.crud.domain.CustomerStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for updating an existing customer.
 *
 * @param name   new display name (required)
 * @param status new lifecycle status (required)
 */
public record UpdateCustomerRequest(
        @NotBlank String name,
        @NotNull CustomerStatus status) {
}
