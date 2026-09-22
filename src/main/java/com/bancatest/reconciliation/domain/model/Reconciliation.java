package com.bancatest.reconciliation.domain.model;

import com.bancatest.reconciliation.domain.exception.IllegalStateTransitionException;
import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The reconciliation between a reported payment and its core transaction. The only
 * aggregate root in this service, and the only entry point for changing any of its data.
 *
 * <p>Invariants it protects:
 *
 * <ol>
 *   <li>A reconciliation is born PENDING, with a valid payment amount and identity.
 *   <li>State only advances through transitions the state machine declares. MATCHED and
 *       MISMATCHED are terminal.
 *   <li>When it resolves, the core amount, the difference and the resolution instant are
 *       populated together. A resolved reconciliation with a missing difference cannot
 *       exist.
 *   <li>The difference always shares its currency with the payment amount.
 * </ol>
 *
 * <p>There are no public setters. State changes through business methods named after the
 * intent behind them. The constructor is private: a new aggregate comes from
 * {@link #start}, and a persisted one from {@link #reconstitute}.
 *
 * <p>Fields that are absent while PENDING are exposed as {@link Optional}. The domain never
 * returns {@code null}.
 */
public final class Reconciliation {

    private final ReconciliationId id;
    private final OperationReference operationReference;
    private final AccountNumber accountNumber;
    private final Amount paymentAmount;
    private final Instant createdAt;

    private Amount coreAmount;
    private AmountDifference difference;
    private ReconciliationStatus status;
    private Instant resolvedAt;

    private Reconciliation(
            ReconciliationId id,
            OperationReference operationReference,
            AccountNumber accountNumber,
            Amount paymentAmount,
            Instant createdAt) {
        this.id = id;
        this.operationReference = operationReference;
        this.accountNumber = accountNumber;
        this.paymentAmount = paymentAmount;
        this.createdAt = createdAt;
    }

    /**
     * Creates a new reconciliation from a reported payment. Validates every invariant and
     * leaves the aggregate PENDING, awaiting its core transaction.
     *
     * <p>This is the only way to bring a genuinely new reconciliation into existence.
     */
    public static Reconciliation start(
            ReconciliationId id,
            OperationReference operationReference,
            AccountNumber accountNumber,
            Amount paymentAmount,
            Instant createdAt) {

        Objects.requireNonNull(id, "ReconciliationId is required to start a reconciliation");
        Objects.requireNonNull(operationReference, "OperationReference is required to start a reconciliation");
        Objects.requireNonNull(accountNumber, "AccountNumber is required to start a reconciliation");
        Objects.requireNonNull(paymentAmount, "Payment amount is required to start a reconciliation");
        Objects.requireNonNull(createdAt, "Creation instant is required to start a reconciliation");

        Reconciliation reconciliation =
                new Reconciliation(id, operationReference, accountNumber, paymentAmount, createdAt);
        reconciliation.status = ReconciliationStatus.PENDING;
        return reconciliation;
    }

    /**
     * Rehydrates an aggregate from persistence, exactly as it was stored.
     *
     * <p>Deliberately does not re-validate business invariants: what was persisted was valid
     * when it was written, and re-running the rules here would reject rows written under an
     * earlier version of them. Reserved for the persistence mapper; nothing else should call
     * it.
     */
    public static Reconciliation reconstitute(ReconciliationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Snapshot is required to reconstitute a reconciliation");

        Reconciliation reconciliation = new Reconciliation(
                snapshot.id(),
                snapshot.operationReference(),
                snapshot.accountNumber(),
                snapshot.paymentAmount(),
                snapshot.createdAt());
        reconciliation.coreAmount = snapshot.coreAmount();
        reconciliation.difference = snapshot.difference();
        reconciliation.status = snapshot.status();
        reconciliation.resolvedAt = snapshot.resolvedAt();
        return reconciliation;
    }

    /**
     * Resolves this reconciliation as agreeing with the core, within tolerance.
     *
     * @throws IllegalStateTransitionException if it is already resolved. Callers check
     *     {@link #isResolved()} first, so in the normal flow this never fires.
     */
    public void match(Amount coreAmount, AmountDifference difference, Instant resolvedAt) {
        resolve(ReconciliationStatus.MATCHED, coreAmount, difference, resolvedAt);
    }

    /**
     * Resolves this reconciliation as disagreeing with the core, recording the exact
     * difference.
     *
     * @throws IllegalStateTransitionException if it is already resolved
     */
    public void markAsMismatched(Amount coreAmount, AmountDifference difference, Instant resolvedAt) {
        resolve(ReconciliationStatus.MISMATCHED, coreAmount, difference, resolvedAt);
    }

    private void resolve(
            ReconciliationStatus target, Amount coreAmount, AmountDifference difference, Instant resolvedAt) {

        if (!status.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
        Objects.requireNonNull(coreAmount, "Core amount is required to resolve a reconciliation");
        Objects.requireNonNull(difference, "Difference is required to resolve a reconciliation");
        Objects.requireNonNull(resolvedAt, "Resolution instant is required to resolve a reconciliation");

        this.coreAmount = coreAmount;
        this.difference = difference;
        this.resolvedAt = resolvedAt;
        this.status = target;
    }

    /**
     * Whether this reconciliation has already reached a terminal state.
     *
     * <p>This is what a use case consults before attempting any transition (FR-020): a core
     * transaction arriving for an already resolved reconciliation is discarded with a
     * warning, not turned into an exception.
     */
    public boolean isResolved() {
        return status.isTerminal();
    }

    public ReconciliationId id() {
        return id;
    }

    public OperationReference operationReference() {
        return operationReference;
    }

    public AccountNumber accountNumber() {
        return accountNumber;
    }

    public Amount paymentAmount() {
        return paymentAmount;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ReconciliationStatus status() {
        return status;
    }

    public Optional<Amount> coreAmount() {
        return Optional.ofNullable(coreAmount);
    }

    public Optional<AmountDifference> difference() {
        return Optional.ofNullable(difference);
    }

    public Optional<Instant> resolvedAt() {
        return Optional.ofNullable(resolvedAt);
    }
}
