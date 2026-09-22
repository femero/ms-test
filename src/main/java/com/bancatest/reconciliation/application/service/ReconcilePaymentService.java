package com.bancatest.reconciliation.application.service;

import com.bancatest.reconciliation.domain.exception.InvalidAmountException;
import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.port.in.ReconcilePaymentCommand;
import com.bancatest.reconciliation.domain.port.in.ReconcilePaymentUseCase;
import com.bancatest.reconciliation.domain.port.out.ProcessedOperationStore;
import com.bancatest.reconciliation.domain.port.out.ReconciliationIdProvider;
import com.bancatest.reconciliation.domain.port.out.ReconciliationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Creates a reconciliation from a reported payment.
 *
 * <p>Idempotency is layered: Redis answers fast and may be wrong, the unique constraint in
 * the database is always right. Both key on the operation reference and on nothing else.
 * A duplicate caught by either layer is treated as successful processing, so the message is
 * acknowledged rather than dead-lettered.
 *
 * <p>Nothing is published here: a PENDING reconciliation produces no event (FR-015).
 */
@ApplicationScoped
public class ReconcilePaymentService implements ReconcilePaymentUseCase {

    private static final Logger LOG = Logger.getLogger(ReconcilePaymentService.class);

    private final ReconciliationRepository repository;
    private final ProcessedOperationStore processedOperations;
    private final ReconciliationIdProvider idProvider;
    private final BusinessValidator validator;

    public ReconcilePaymentService(
            ReconciliationRepository repository,
            ProcessedOperationStore processedOperations,
            ReconciliationIdProvider idProvider,
            BusinessValidator validator) {
        this.repository = repository;
        this.processedOperations = processedOperations;
        this.idProvider = idProvider;
        this.validator = validator;
    }

    @Override
    @Transactional
    public void reconcile(ReconcilePaymentCommand command) {
        // Invalid input propagates: the consumer lets it reach the dead-letter queue.
        validator.requireAcceptable(command.amount());

        if (!processedOperations.markAsProcessed(command.operationReference())) {
            LOG.infof("Payment %s already processed, discarding duplicate", command.operationReference());
            return;
        }

        if (repository.findByOperationReference(command.operationReference()).isPresent()) {
            LOG.infof("Reconciliation %s already exists, discarding duplicate payment", command.operationReference());
            return;
        }

        Reconciliation reconciliation = Reconciliation.start(
                idProvider.nextId(),
                command.operationReference(),
                command.accountNumber(),
                command.amount(),
                command.occurredAt());

        repository.save(reconciliation);
        LOG.infof("Reconciliation %s created as PENDING", command.operationReference());
    }

    /**
     * Business validation shared by both write use cases: an amount must be strictly
     * positive and denominated in the single supported currency.
     *
     * <p>Zero is structurally a valid {@link Amount}, so rejecting it is a rule, not a type
     * constraint, and it belongs here rather than in the value object.
     */
    @ApplicationScoped
    public static class BusinessValidator {

        private static final String SUPPORTED_CURRENCY = "PEN";

        public void requireAcceptable(Amount amount) {
            if (!amount.isPositive()) {
                throw new InvalidAmountException("Amount must be strictly positive, got: " + amount);
            }
            if (!SUPPORTED_CURRENCY.equals(amount.currency().code())) {
                throw new InvalidAmountException("Only " + SUPPORTED_CURRENCY + " is supported, got: "
                        + amount.currency().code());
            }
        }
    }
}
