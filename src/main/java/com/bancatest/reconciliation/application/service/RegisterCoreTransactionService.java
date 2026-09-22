package com.bancatest.reconciliation.application.service;

import com.bancatest.reconciliation.application.service.ReconcilePaymentService.BusinessValidator;
import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import com.bancatest.reconciliation.domain.port.in.RegisterCoreTransactionCommand;
import com.bancatest.reconciliation.domain.port.in.RegisterCoreTransactionUseCase;
import com.bancatest.reconciliation.domain.port.out.ReconciliationEventPublisher;
import com.bancatest.reconciliation.domain.port.out.ReconciliationRepository;
import com.bancatest.reconciliation.domain.port.out.ToleranceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Resolves a reconciliation against its core transaction.
 *
 * <p>The order of the checks is the point of this class. State is inspected <em>before</em>
 * any transition is attempted, so an already-resolved reconciliation leads to a discard with
 * a warning rather than to an exception (FR-020). Were it the other way round, every
 * redelivered message would raise {@code IllegalStateTransitionException} and land in the
 * dead-letter queue instead of being quietly absorbed.
 *
 * <p>Publication happens after the transaction commits, never inside it.
 */
@ApplicationScoped
public class RegisterCoreTransactionService implements RegisterCoreTransactionUseCase {

    private static final Logger LOG = Logger.getLogger(RegisterCoreTransactionService.class);

    private final ReconciliationRepository repository;
    private final ReconciliationEventPublisher publisher;
    private final BusinessValidator validator;
    private final Amount tolerance;
    private final Clock clock;

    public RegisterCoreTransactionService(
            ReconciliationRepository repository,
            ReconciliationEventPublisher publisher,
            BusinessValidator validator,
            ToleranceProvider toleranceProvider) {
        this.repository = repository;
        this.publisher = publisher;
        this.validator = validator;
        this.tolerance = toleranceProvider.tolerance();
        this.clock = Clock.systemUTC();
    }

    @Override
    public void register(RegisterCoreTransactionCommand command) {
        validator.requireAcceptable(command.amount());

        Optional<Reconciliation> resolved = resolveInTransaction(command);
        // Outside the transaction on purpose: the event is published only once the new
        // state is durable. If this step fails the event is lost, by accepted design.
        resolved.ifPresent(this::publish);
    }

    /**
     * Loads, resolves and persists, inside a transaction that commits when this returns.
     *
     * <p>The self-invocation above is deliberate and it does work here, which is worth
     * stating because the opposite is true on other stacks. Quarkus ArC applies interceptors
     * by generating a <em>subclass</em> that overrides the intercepted method, so {@code this}
     * already refers to the intercepted instance and the call dispatches through the
     * interceptor chain. On a proxy-based container such as Spring the same shape would
     * silently skip {@code @Transactional}, the write would never be flushed, and the event
     * would be published for a reconciliation still PENDING in the database.
     *
     * <p>Two conditions keep it working: the method must stay non-private and non-final, or
     * the subclass cannot override it. {@code InMemoryReconciliationRepository} asserts an
     * active transaction on every save, so a regression here fails the suite rather than
     * surfacing in production.
     */
    @Transactional
    protected Optional<Reconciliation> resolveInTransaction(RegisterCoreTransactionCommand command) {
        Optional<Reconciliation> found = repository.findByOperationReference(command.operationReference());

        if (found.isEmpty()) {
            LOG.warnf("No reconciliation for %s, discarding core transaction", command.operationReference());
            return Optional.empty();
        }

        Reconciliation reconciliation = found.get();

        if (reconciliation.isResolved()) {
            LOG.warnf(
                    "Reconciliation %s already resolved as %s, discarding core transaction",
                    command.operationReference(), reconciliation.status());
            return Optional.empty();
        }

        AmountDifference difference = reconciliation.paymentAmount().subtract(command.amount());
        Instant now = clock.instant();

        if (difference.isWithinTolerance(tolerance)) {
            reconciliation.match(command.amount(), difference, now);
            LOG.infof("Reconciliation %s matched, difference %s", command.operationReference(), difference);
        } else {
            reconciliation.markAsMismatched(command.amount(), difference, now);
            LOG.warnf("Discrepancy on %s, difference %s", command.operationReference(), difference);
        }

        repository.save(reconciliation);
        return Optional.of(reconciliation);
    }

    private void publish(Reconciliation reconciliation) {
        if (reconciliation.status() == ReconciliationStatus.MATCHED) {
            publisher.publishMatched(reconciliation);
        } else {
            publisher.publishMismatched(reconciliation);
        }
    }
}
