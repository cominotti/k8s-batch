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
 * Route definitions that proxy incoming requests to the {@code k8s-batch-crud} backend.
 *
 * <p>Two route groups for Customer and Account CRUD APIs, both circuit-broken.
 * Rate limiting is handled upstream by
 * {@link com.cominotti.k8sbatch.gateway.adapters.filteringrequests.ratelimiting.IpRateLimitFilter
 * IpRateLimitFilter}.
 */
@Configuration(proxyBeanMethods = false)
class CrudAppRouteConfig {

    private static final Logger log = LoggerFactory.getLogger(CrudAppRouteConfig.class);

    /**
     * Routes for the Customer API with circuit breaking.
     *
     * @param props gateway configuration properties
     * @return router function for customer API routes
     */
    @Bean
    RouterFunction<ServerResponse> customerApiRoutes(GatewayProperties props) {
        log.info("Configuring customer API routes | backendUrl={} | circuitBreaker={}",
                props.crudBackendUrl(), props.crudCircuitBreakerName());
        return route("customer-api")
                .route(path("/api/customers/**"), http())
                .before(uri(props.crudBackendUrl()))
                .filter(circuitBreaker(config -> config
                        .setId(props.crudCircuitBreakerName())
                        .setStatusCodes("500", "502", "503", "504")))
                .build();
    }

    /**
     * Routes for the Account API with circuit breaking.
     *
     * @param props gateway configuration properties
     * @return router function for account API routes
     */
    @Bean
    RouterFunction<ServerResponse> accountApiRoutes(GatewayProperties props) {
        log.info("Configuring account API routes | backendUrl={} | circuitBreaker={}",
                props.crudBackendUrl(), props.crudCircuitBreakerName());
        return route("account-api")
                .route(path("/api/accounts/**"), http())
                .before(uri(props.crudBackendUrl()))
                .filter(circuitBreaker(config -> config
                        .setId(props.crudCircuitBreakerName())
                        .setStatusCodes("500", "502", "503", "504")))
                .build();
    }
}
