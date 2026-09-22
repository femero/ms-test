package com.bancatest.reconciliation.domain.port.out;

import com.bancatest.reconciliation.domain.model.vo.OperationReference;

/**
 * Outbound port for the fast idempotency pre-filter.
 *
 * <p>Deliberately named after operations, not events. Idempotency is anchored on the
 * operation reference and on nothing else; the event identifier carried by incoming messages
 * is informational. A port named "event store" would invite exactly the mistake this
 * naming avoids.
 *
 * <p>Contract: {@code markAsProcessed} returns {@code true} when this call is the one that
 * marked the reference, and {@code false} when it was already marked.
 *
 * <p>This is an optimisation, not a guarantee. The implementation may forget entries after a
 * TTL, and may be unavailable. When it is, the unique constraint in the database still
 * rejects the duplicate, so correctness never depends on this port answering accurately.
 */
public interface ProcessedOperationStore {

    boolean markAsProcessed(OperationReference operationReference);
}
