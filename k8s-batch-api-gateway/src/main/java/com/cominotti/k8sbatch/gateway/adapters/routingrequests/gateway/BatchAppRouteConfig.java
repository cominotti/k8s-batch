// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.adapters.routingrequests.gateway;

import com.cominotti.k8sbatch.gateway.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.path;

/**
 * Route definitions that proxy incoming requests to the {@code k8s-batch-jobs} backend.
 *
 * <p>Two route groups with different filter stacks:
 * <ul>
 *   <li><strong>Job API</strong> ({@code /api/jobs/**}) — circuit-broken. Rate limiting is handled
 *       by {@link com.cominotti.k8sbatch.gateway.adapters.filteringrequests.ratelimiting.IpRateLimitFilter
 *       IpRateLimitFilter} (a Servlet filter) because Spring Cloud Gateway MVC's built-in
 *       {@code Bucket4jFilterFunctions.rateLimit()} requires a distributed
 *       {@code AsyncProxyManager} bean.</li>
 *   <li><strong>Hello</strong> ({@code /hello}) — lightweight health probe, no rate limiting or
 *       circuit breaking</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class BatchAppRouteConfig {

    private static final Logger log = LoggerFactory.getLogger(BatchAppRouteConfig.class);

    /**
     * Routes for the batch job API with circuit breaking.
     *
     * <p>Covers both {@code POST /api/jobs/{jobName}} (async job launch) and
     * {@code GET /api/jobs/{jobName}/executions/{executionId}} (status polling).
     * Rate limiting is applied upstream by {@code IpRateLimitFilter}.
     *
     * @param props gateway configuration properties
     * @return router function for the job API routes
     */
    @Bean
    RouterFunction<ServerResponse> jobApiRoutes(GatewayProperties props) {
        log.info("Configuring job API routes | backendUrl={} | circuitBreaker={}",
                props.backendUrl(), props.circuitBreakerName());
        return route("job-api")
                .route(path("/api/jobs/**"), http())
                .before(uri(props.backendUrl()))
                .filter(circuitBreaker(config -> config
                        .setId(props.circuitBreakerName())
                        .setStatusCodes("500", "502", "503", "504")))
                .build();
    }

    /**
     * Route for the hello health-check endpoint. No rate limiting or circuit breaking — this is a
     * lightweight probe that should always pass through.
     *
     * @param props gateway configuration properties
     * @return router function for the hello route
     */
    @Bean
    RouterFunction<ServerResponse> helloRoute(GatewayProperties props) {
        log.info("Configuring hello route | backendUrl={}", props.backendUrl());
        return route("hello")
                .route(path("/hello"), http())
                .before(uri(props.backendUrl()))
                .build();
    }
}
