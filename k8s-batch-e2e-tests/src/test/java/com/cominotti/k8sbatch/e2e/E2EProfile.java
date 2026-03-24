// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the Helm deployment profile used by an E2E test class.
 *
 * <p>Used by {@link E2EProfileClassOrderer} to group test classes by profile,
 * minimizing expensive K3s teardown/redeploy cycles during the E2E test suite.
 * Classes sharing the same profile run consecutively; profile switches happen
 * at most once per unique profile value.
 *
 * @see E2EProfileClassOrderer
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface E2EProfile {

    /**
     * The Helm values filename that this test class deploys.
     * Must match the return value of {@link AbstractE2ETest#valuesFile()}.
     *
     * @return the values filename (e.g., {@code "e2e-remote.yaml"})
     */
    String value();
}
