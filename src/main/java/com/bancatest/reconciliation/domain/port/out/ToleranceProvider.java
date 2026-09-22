package com.bancatest.reconciliation.domain.port.out;

import com.bancatest.reconciliation.domain.model.vo.Amount;

/**
 * Outbound port supplying the configured tolerance.
 *
 * <p>Exists because the application layer must not import infrastructure, and the tolerance
 * lives in a configuration interface that does. The value crosses the boundary already
 * converted into an {@link Amount}, so no layer inward of this port learns that a
 * configuration framework exists.
 *
 * <p>A fixed amount with a currency, never a percentage: the comparison is a direct
 * subtraction and no division happens anywhere.
 */
public interface ToleranceProvider {

    Amount tolerance();
}
