// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto;

import com.cominotti.k8sbatch.crud.domain.Customer;
import com.cominotti.k8sbatch.crud.domain.CustomerStatus;

import java.time.Instant;

/**
 * Response body for a customer without nested accounts.
 *
 * @param id        surrogate ID
 * @param name      display name
 * @param email     unique email
 * @param status    lifecycle status
 * @param createdAt creation timestamp
 * @param updatedAt last modification timestamp
 */
public record CustomerResponse(
        Long id,
        String name,
        String email,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Maps a JPA entity to a response DTO.
     *
     * @param customer the entity
     * @return the DTO
     */
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(), customer.getName(), customer.getEmail(),
                customer.getStatus(), customer.getCreatedAt(), customer.getUpdatedAt());
    }
}
