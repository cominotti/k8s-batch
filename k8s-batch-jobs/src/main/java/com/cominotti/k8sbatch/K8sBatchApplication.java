// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point for the k8s-batch application.
 *
 * <p>{@code @ConfigurationPropertiesScan} is required here because {@link com.cominotti.k8sbatch.batch.common.domain.BatchPartitionProperties
 * BatchPartitionProperties} is a record-based {@code @ConfigurationProperties} class that must be
 * discovered via classpath scanning (it cannot use {@code @EnableConfigurationProperties} on itself).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class K8sBatchApplication {

    /**
     * Application entry point.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(K8sBatchApplication.class, args);
    }
}
