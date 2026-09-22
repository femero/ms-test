package com.bancatest.reconciliation.domain.port.in;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import java.util.Optional;

/**
 * Inbound port for looking up a reconciliation by its operation reference.
 *
 * <p>Returns empty when nothing matches. Not finding a reconciliation is an ordinary
 * outcome, not an error, so the absence is modelled in the return type rather than thrown.
 * Callers must not unwrap with {@code isPresent()} followed by {@code get()}.
 */
public interface QueryReconciliationUseCase {

    Optional<Reconciliation> findByOperationReference(OperationReference operationReference);
}
