// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rules engine PoC configuration bound to the {@code batch.rules.*} properties.
 *
 * <p>Lives in the config zone because it uses Spring Boot's {@code @ConfigurationProperties}
 * annotation, keeping the domain package free of framework dependencies.
 *
 * @param engine    rules engine to use: {@code "drools"} or {@code "evrete"}
 * @param chunkSize items per transaction in the chunk-oriented step (default: 100)
 */
@ConfigurationProperties(prefix = "batch.rules")
public record RulesEngineProperties(String engine, int chunkSize) {

    /**
     * Applies defaults for missing or invalid configuration values.
     *
     * @param engine    raw engine name from configuration (may be {@code null})
     * @param chunkSize raw chunk size (may be zero or negative)
     */
    public RulesEngineProperties {
        if (engine == null || engine.isBlank()) {
            engine = "drools";
        }
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
    }
}
