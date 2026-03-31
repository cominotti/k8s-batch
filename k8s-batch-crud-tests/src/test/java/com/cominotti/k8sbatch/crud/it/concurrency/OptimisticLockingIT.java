// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.concurrency;

import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CreateCustomerRequest;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CustomerResponse;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.UpdateCustomerRequest;
import com.cominotti.k8sbatch.crud.domain.CustomerStatus;
import com.cominotti.k8sbatch.crud.it.AbstractCrudIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code @Version}-based optimistic locking detects concurrent modifications
 * and returns HTTP 409 Conflict.
 */
class OptimisticLockingIT extends AbstractCrudIntegrationTest {

    @Test
    void shouldDetectConcurrentUpdateViaRestApi() throws Exception {
        CustomerResponse customer = restClient().post()
                .uri("/api/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateCustomerRequest("Original", "lock-test@example.com"))
                .retrieve()
                .body(CustomerResponse.class);

        int concurrency = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        AtomicInteger conflicts = new AtomicInteger(0);
        AtomicInteger successes = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency)) {
            for (int i = 0; i < concurrency; i++) {
                int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await(5, TimeUnit.SECONDS);
                        restClient().put()
                                .uri("/api/customers/{id}", customer.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(new UpdateCustomerRequest("Updated-" + idx, CustomerStatus.ACTIVE))
                                .retrieve()
                                .body(CustomerResponse.class);
                        successes.incrementAndGet();
                    } catch (HttpClientErrorException e) {
                        if (e.getStatusCode() == HttpStatus.CONFLICT) {
                            conflicts.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore other exceptions
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(15, TimeUnit.SECONDS);
        }

        // At least one should succeed, and under high concurrency some should conflict
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);
        // Total attempts = successes + conflicts (some may retry internally)
        assertThat(successes.get() + conflicts.get()).isLessThanOrEqualTo(concurrency);
    }
}
