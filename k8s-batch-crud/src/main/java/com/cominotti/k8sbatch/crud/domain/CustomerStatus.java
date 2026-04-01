// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain;

import java.util.Set;

/**
 * Lifecycle status of a {@link Customer}.
 *
 * <p>Allowed transitions enforce a one-directional lifecycle:
 * <pre>
 *   ACTIVE → SUSPENDED → CLOSED
 *   ACTIVE → CLOSED
 *   SUSPENDED → ACTIVE  (reactivation)
 * </pre>
 */
public enum CustomerStatus {

    ACTIVE(Set.of()),     // placeholder, overwritten in static initializer
    SUSPENDED(Set.of()),  // placeholder, overwritten in static initializer
    CLOSED(Set.of());     // terminal state — no transitions allowed

    private Set<CustomerStatus> allowedTargets;

    CustomerStatus(Set<CustomerStatus> allowedTargets) {
        this.allowedTargets = allowedTargets;
    }

    static {
        ACTIVE.allowedTargets = Set.of(SUSPENDED, CLOSED);
        SUSPENDED.allowedTargets = Set.of(ACTIVE, CLOSED);
        CLOSED.allowedTargets = Set.of();
    }

    /**
     * Checks whether a transition from this status to the given target is allowed.
     *
     * @param target the desired target status
     * @return {@code true} if the transition is valid
     */
    public boolean canTransitionTo(CustomerStatus target) {
        return allowedTargets.contains(target);
    }
}
