// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto;

import com.cominotti.k8sbatch.crud.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for updating an account's lifecycle status.
 *
 * @param status the new status
 */
public record UpdateAccountStatusRequest(@NotNull AccountStatus status) {
}
