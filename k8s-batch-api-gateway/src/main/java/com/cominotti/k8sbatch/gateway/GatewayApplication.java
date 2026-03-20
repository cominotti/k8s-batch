// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Cloud Gateway Server MVC entry point.
 *
 * <p>Routes all incoming HTTP requests to the {@code k8s-batch-jobs} backend with cross-cutting
 * concerns: structured request logging, Bucket4j rate limiting, and Resilience4j circuit breaking.
 * Runs on the Servlet stack with virtual threads enabled.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    /**
     * Starts the API gateway.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
