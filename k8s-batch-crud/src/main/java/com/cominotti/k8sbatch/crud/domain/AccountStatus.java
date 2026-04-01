// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain;

import java.util.Set;

/**
 * Lifecycle status of an {@link Account}.
 *
 * <p>Allowed transitions:
 * <pre>
 *   ACTIVE → FROZEN → CLOSED
 *   ACTIVE → CLOSED
 *   FROZEN → ACTIVE  (reactivation)
 * </pre>
 */
public enum AccountStatus {

    ACTIVE(Set.of()),   // placeholder, overwritten in static initializer
    FROZEN(Set.of()),   // placeholder, overwritten in static initializer
    CLOSED(Set.of());   // terminal state — no transitions allowed

    private Set<AccountStatus> allowedTargets;

    AccountStatus(Set<AccountStatus> allowedTargets) {
        this.allowedTargets = allowedTargets;
    }

    static {
        ACTIVE.allowedTargets = Set.of(FROZEN, CLOSED);
        FROZEN.allowedTargets = Set.of(ACTIVE, CLOSED);
        CLOSED.allowedTargets = Set.of();
    }

    /**
     * Checks whether a transition from this status to the given target is allowed.
     *
     * @param target the desired target status
     * @return {@code true} if the transition is valid
     */
    public boolean canTransitionTo(AccountStatus target) {
        return allowedTargets.contains(target);
    }
}
