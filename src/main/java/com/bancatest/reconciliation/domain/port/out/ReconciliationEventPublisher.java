package com.bancatest.reconciliation.domain.port.out;

import com.bancatest.reconciliation.domain.model.Reconciliation;

/**
 * Outbound port for publishing a resolved reconciliation.
 *
 * <p>Contract: publication happens only after the new state has been persisted, and at most
 * once. There is no outbox, so a failure between the commit and the publication loses the
 * event without reverting the reconciliation. That loss is an accepted risk, documented in
 * the README, and must not trigger a retry or a compensation.
 */
public interface ReconciliationEventPublisher {

    void publishMatched(Reconciliation reconciliation);

    void publishMismatched(Reconciliation reconciliation);
}
