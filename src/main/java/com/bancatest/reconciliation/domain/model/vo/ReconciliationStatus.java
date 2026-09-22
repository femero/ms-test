package com.bancatest.reconciliation.domain.model.vo;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a reconciliation, together with the transitions it permits.
 *
 * <p>The transition table is declared here and nowhere else, so there is a single place to
 * read and a single place to test:
 *
 * <pre>
 *   PENDING    -> MATCHED
 *   PENDING    -> MISMATCHED
 *   MATCHED    -> (terminal)
 *   MISMATCHED -> (terminal)
 * </pre>
 *
 * <p>Of the nine possible ordered pairs only those two are legal. Everything else,
 * self-transitions included, is rejected.
 *
 * <p>Persisted via {@link #name()}, never by ordinal: an ordinal silently reinterprets every
 * stored row the day someone reorders the constants.
 */
public enum ReconciliationStatus {

    /** Awaiting the core transaction. */
    PENDING,

    /** Figures agree, within tolerance. Terminal. */
    MATCHED,

    /** Figures disagree beyond tolerance, or the core charged more. Terminal. */
    MISMATCHED;

    private static final Map<ReconciliationStatus, Set<ReconciliationStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(MATCHED, MISMATCHED),
            MATCHED, EnumSet.noneOf(ReconciliationStatus.class),
            MISMATCHED, EnumSet.noneOf(ReconciliationStatus.class));

    /**
     * Whether moving from this state to {@code target} is declared as legal.
     *
     * <p>Callers are expected to consult this before mutating an aggregate. The aggregate
     * enforces it too, but as a safety net rather than as control flow.
     */
    public boolean canTransitionTo(ReconciliationStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.get(this).contains(target);
    }

    /** Whether this state admits no further transitions. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
