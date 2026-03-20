// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.gateway.it.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cominotti.k8sbatch.gateway.adapters.filteringrequests.logging.StructuredRequestLoggingFilter;
import com.cominotti.k8sbatch.gateway.it.AbstractGatewayIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@link StructuredRequestLoggingFilter} produces structured
 * {@code key=value | key=value} log output for every proxied request.
 */
class RequestLoggingFilterIT extends AbstractGatewayIT {

    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger filterLogger;
    private Level originalLevel;

    @BeforeEach
    void attachLogAppender() {
        filterLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                StructuredRequestLoggingFilter.class);
        // Override to INFO so the filter's log messages are captured even when
        // the test profile defaults to WARN
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.INFO);
        logAppender = new ListAppender<>();
        logAppender.start();
        filterLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        filterLogger.detachAppender(logAppender);
        filterLogger.setLevel(originalLevel);
        logAppender.stop();
    }

    @Test
    void shouldLogStructuredRequestAndResponseDetails() {
        wireMock.stubFor(get(urlEqualTo("/hello"))
                .willReturn(okJson("{\"message\":\"Hello from k8s-batch!\"}")));

        gatewayClient().get()
                .uri("/hello")
                .retrieve()
                .body(String.class);

        List<String> logMessages = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Verify "received" log line with structured fields
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("Gateway request received")
                        && msg.contains("method=GET")
                        && msg.contains("uri=/hello"));

        // Verify "completed" log line with status and duration
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("Gateway request completed")
                        && msg.contains("method=GET")
                        && msg.contains("uri=/hello")
                        && msg.contains("status=200")
                        && msg.contains("durationMs="));
    }
}
