package com.bancatest.reconciliation.domain.model;

import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import java.time.Instant;

/**
 * The complete persisted state of a reconciliation, used solely to rehydrate an aggregate.
 *
 * <p>Exists because rehydration needs every field at once, and passing nine loose arguments
 * is both unreadable and a violation of the project's parameter limit. Grouping them makes
 * the call site state what it is doing: handing over a stored snapshot.
 *
 * <p>{@code coreAmount}, {@code difference} and {@code resolvedAt} are null while the
 * reconciliation is PENDING. This is the one place in the domain where nulls are tolerated,
 * because it mirrors nullable columns; the aggregate re-exposes them as {@link java.util.Optional}.
 *
 * <p>Reserved for the persistence mapper. Nothing else should build one.
 */
public record ReconciliationSnapshot(
        ReconciliationId id,
        OperationReference operationReference,
        AccountNumber accountNumber,
        Amount paymentAmount,
        Instant createdAt,
        Amount coreAmount,
        AmountDifference difference,
        ReconciliationStatus status,
        Instant resolvedAt) {}
