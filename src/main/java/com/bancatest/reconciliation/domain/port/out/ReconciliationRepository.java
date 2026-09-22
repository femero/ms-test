package com.bancatest.reconciliation.domain.port.out;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import java.util.Optional;

/**
 * Outbound port for storing and retrieving reconciliations.
 *
 * <p>Speaks in terms of the aggregate, never of tables or rows. There is one repository per
 * aggregate root, not one per table.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@code save} persists a new aggregate or the new state of an existing one. It is
 *       expected to fail when the operation reference already exists: that unique constraint
 *       is the real idempotency guarantee (FR-018), and the caller treats such a failure as
 *       a duplicate successfully discarded, not as an error.
 *   <li>{@code findByOperationReference} returns empty when nothing matches. An absent
 *       reconciliation is an ordinary outcome, not an exceptional one, which is why this
 *       returns {@link Optional} rather than null or an exception.
 * </ul>
 */
public interface ReconciliationRepository {

    void save(Reconciliation reconciliation);

    Optional<Reconciliation> findByOperationReference(OperationReference operationReference);
}
