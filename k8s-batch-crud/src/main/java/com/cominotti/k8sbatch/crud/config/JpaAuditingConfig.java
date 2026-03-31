// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA auditing so that {@code @CreatedDate} and {@code @LastModifiedDate} annotations
 * on entity fields are populated automatically by Spring Data.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
class JpaAuditingConfig {
}
