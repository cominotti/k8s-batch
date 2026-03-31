// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.config;

/**
 * Docker image constants for CRUD service integration tests.
 *
 * <p>Mirrors the pattern of {@code TestContainerImages} in the batch integration-tests module.
 * Each module maintains its own constants to avoid cross-module coupling.
 */
public final class CrudTestContainerImages {

    /** MySQL 8.4 image used for the shared database container. */
    public static final String MYSQL_IMAGE = "mysql:8.4";

    private CrudTestContainerImages() {
    }
}
