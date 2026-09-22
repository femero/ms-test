package com.bancatest.reconciliation.domain.port.in;

import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import java.time.Instant;

/**
 * A core transaction, already translated into domain types.
 *
 * <p>The core transaction is never persisted and is not an entity: it arrives, it is
 * compared against the reported payment, and it is discarded.
 */
public record RegisterCoreTransactionCommand(
        OperationReference operationReference, Amount amount, Instant occurredAt) {}
