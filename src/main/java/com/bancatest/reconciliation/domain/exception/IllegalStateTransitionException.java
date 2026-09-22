package com.bancatest.reconciliation.domain.exception;

import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;

/**
 * Raised when a state transition that the state machine does not declare is attempted.
 *
 * <p>This is a safety net, not a control-flow mechanism. The normal flow checks
 * {@code isResolved()} before attempting any transition, so a well-behaved use case never
 * triggers this. If it does surface, it means an aggregate was mutated without that check
 * and the message will legitimately end up in the dead-letter queue.
 *
 * <p>Carries both the source and the target state, because knowing only that "a transition
 * failed" is useless when diagnosing a message that reached the DLQ.
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final ReconciliationStatus from;
    private final ReconciliationStatus to;

    public IllegalStateTransitionException(ReconciliationStatus from, ReconciliationStatus to) {
        super("Illegal reconciliation state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public ReconciliationStatus from() {
        return from;
    }

    public ReconciliationStatus to() {
        return to;
    }
}
