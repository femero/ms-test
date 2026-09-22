package com.bancatest.reconciliation.domain.port.in;

import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import java.time.Instant;

/**
 * A reported payment, already translated into domain types.
 *
 * <p>The event identifier that travels in the integration contract is deliberately absent.
 * Idempotency is anchored on the operation reference and on nothing else, so carrying the
 * event id into the domain would only invite someone to key on it later.
 *
 * @param occurredAt informational; no rule in the current scope depends on it
 */
public record ReconcilePaymentCommand(
        OperationReference operationReference, AccountNumber accountNumber, Amount amount, Instant occurredAt) {}
