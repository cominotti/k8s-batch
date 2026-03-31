// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for creating a new customer.
 *
 * @param name  display name (required)
 * @param email unique email address (required, must be valid format)
 */
public record CreateCustomerRequest(
        @NotBlank String name,
        @NotBlank @Email String email) {
}
