package com.bancatest.reconciliation.domain.port.in;

/**
 * Inbound port for a reported payment.
 *
 * <p>Creates a PENDING reconciliation when the operation reference is new, and discards the
 * command without effect when it already exists (FR-019). A discard is successful
 * processing, not an error.
 *
 * @throws com.bancatest.reconciliation.domain.exception.InvalidAmountException if the
 *     payment breaks a business validation rule; the caller lets it propagate so the
 *     message reaches the dead-letter queue (FR-022)
 */
public interface ReconcilePaymentUseCase {

    void reconcile(ReconcilePaymentCommand command);
}
