// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class K8sBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(K8sBatchApplication.class, args);
    }
}
