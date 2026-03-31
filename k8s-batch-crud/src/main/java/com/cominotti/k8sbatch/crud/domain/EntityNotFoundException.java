// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain;

/**
 * Thrown when a requested entity does not exist. Translated to HTTP 404 by the
 * {@link com.cominotti.k8sbatch.crud.adapters.handlingerrors.rest.GlobalExceptionHandler}.
 */
public class EntityNotFoundException extends RuntimeException {

    /**
     * Creates an exception for a missing entity identified by type and ID.
     *
     * @param entityType the entity class name
     * @param id         the identifier that was not found
     */
    public EntityNotFoundException(String entityType, Object id) {
        super(entityType + " not found: " + id);
    }
}
