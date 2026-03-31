// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Customer/Account CRUD microservice.
 *
 * <p>Runs as a standalone Spring Boot application alongside the batch service, sharing the same
 * MySQL database. Schema migrations are managed by Liquibase changelogs from {@code k8s-batch-common}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CrudApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrudApplication.class, args);
    }
}
