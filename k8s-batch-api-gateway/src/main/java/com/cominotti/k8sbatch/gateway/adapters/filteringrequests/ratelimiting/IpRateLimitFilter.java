// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.adapters.filteringrequests.ratelimiting;

import com.cominotti.k8sbatch.gateway.GatewayProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiting filter using Bucket4j's local (in-memory) token bucket algorithm.
 *
 * <p>Applies only to the job API routes ({@code /api/jobs/**}). The {@code /hello} health-check
 * endpoint is exempt. Each client IP gets its own bucket with the capacity and period configured
 * in {@link GatewayProperties.RateLimitProperties}.
 *
 * <p>This uses Bucket4j's local {@link Bucket} API directly, avoiding the Spring Cloud Gateway
 * {@code Bucket4jFilterFunctions.rateLimit()} which requires a distributed
 * {@code AsyncProxyManager} bean (e.g., Redis).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IpRateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimitFilter.class);
    private static final String RATE_LIMITED_PATH_PREFIX = "/api/jobs";

    private final int capacity;
    private final Duration period;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Creates the rate limiting filter with configuration from {@link GatewayProperties}.
     *
     * @param props gateway configuration properties
     */
    public IpRateLimitFilter(GatewayProperties props) {
        this.capacity = props.rateLimit().capacity();
        this.period = Duration.ofSeconds(props.rateLimit().periodSeconds());
        log.info("Rate limiting configured | capacity={} | periodSeconds={}",
                capacity, props.rateLimit().periodSeconds());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String uri = httpRequest.getRequestURI();

        // Only rate-limit job API routes, not health checks
        if (!uri.startsWith(RATE_LIMITED_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = httpRequest.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(clientIp, this::createBucket);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded | clientIp={} | uri={}", clientIp, uri);
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Too Many Requests\"}");
        }
    }

    private Bucket createBucket(String _clientIp) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, period)
                        .build())
                .build();
    }
}
