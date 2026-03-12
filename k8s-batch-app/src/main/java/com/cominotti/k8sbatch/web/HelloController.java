// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Simple health-check endpoint used by E2E tests to verify the deployment is reachable. */
@RestController
public class HelloController {

    /**
     * Returns a simple greeting message.
     *
     * @return single-entry map with key {@code "message"}
     */
    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from k8s-batch!");
    }
}
