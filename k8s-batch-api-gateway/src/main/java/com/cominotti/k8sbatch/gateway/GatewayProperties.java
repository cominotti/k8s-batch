// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway configuration bound to the {@code gateway.*} properties.
 *
 * @param backendUrl         base URL of the {@code k8s-batch-jobs} backend (default: {@code http://localhost:8080})
 * @param rateLimit          rate limiting configuration for the job API routes
 * @param circuitBreakerName name of the Resilience4j circuit breaker instance (default: {@code batchBackend}).
 *                           Must match a key under {@code resilience4j.circuitbreaker.instances.*} in YAML
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String backendUrl,
        RateLimitProperties rateLimit,
        String circuitBreakerName) {

    // Compact constructor applies defaults because records cannot use field initializers
    public GatewayProperties {
        if (backendUrl == null || backendUrl.isBlank()) {
            backendUrl = "http://localhost:8080";
        }
        if (rateLimit == null) {
            rateLimit = new RateLimitProperties(100, 60);
        }
        if (circuitBreakerName == null || circuitBreakerName.isBlank()) {
            circuitBreakerName = "batchBackend";
        }
    }

    /**
     * Bucket4j rate limiting configuration for the job API routes.
     *
     * @param capacity      maximum number of requests allowed per period (default: 100)
     * @param periodSeconds refill period in seconds (default: 60)
     */
    public record RateLimitProperties(int capacity, int periodSeconds) {

        public RateLimitProperties {
            if (capacity <= 0) {
                capacity = 100;
            }
            if (periodSeconds <= 0) {
                periodSeconds = 60;
            }
        }
    }
}
