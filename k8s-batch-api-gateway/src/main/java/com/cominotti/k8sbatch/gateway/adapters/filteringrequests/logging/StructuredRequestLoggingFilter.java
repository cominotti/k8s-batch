// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.adapters.filteringrequests.logging;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that logs every incoming gateway request using the project's structured
 * {@code key=value | key=value} format.
 *
 * <p>Logs two events per request: one when the request arrives and one after the response is sent,
 * including the HTTP status code and elapsed duration in milliseconds.
 */
@Component
public class StructuredRequestLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(StructuredRequestLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        long startNanos = System.nanoTime();

        String method = httpRequest.getMethod();
        String uri = httpRequest.getRequestURI();

        log.info("Gateway request received | method={} | uri={}", method, uri);

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            int status = ((HttpServletResponse) response).getStatus();
            log.info("Gateway request completed | method={} | uri={} | status={} | durationMs={}",
                    method, uri, status, durationMs);
        }
    }
}
