// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HelloController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from k8s-batch!");
    }
}
