// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

import java.util.Comparator;

/**
 * Orders E2E test classes by their {@link E2EProfile} annotation value, grouping
 * all classes that share the same Helm values file together. Within each profile
 * group, classes are ordered alphabetically by simple class name.
 *
 * <p>This eliminates unnecessary K3s teardown/redeploy cycles: instead of
 * profile switches at every alphabetical boundary (e.g., standalone between
 * remote classes), all remote tests run first, then all standalone tests.
 *
 * <p>Registered globally via {@code junit-platform.properties}:
 * <pre>
 * junit.jupiter.testclass.order.default=com.cominotti.k8sbatch.e2e.E2EProfileClassOrderer
 * </pre>
 *
 * <p>Classes without {@code @E2EProfile} sort after annotated classes.
 */
public class E2EProfileClassOrderer implements ClassOrderer {

    /**
     * Sorts class descriptors by profile group, then alphabetically within each group.
     *
     * @param context the class orderer context containing the descriptors to sort
     */
    @Override
    public void orderClasses(ClassOrdererContext context) {
        context.getClassDescriptors().sort(
                Comparator.comparing(
                                (ClassDescriptor d) -> d.findAnnotation(E2EProfile.class)
                                        .map(E2EProfile::value)
                                        .orElse("\uFFFF"))
                        .thenComparing(d -> d.getTestClass().getSimpleName()));
    }
}
