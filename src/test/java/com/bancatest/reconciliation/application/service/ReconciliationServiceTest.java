package com.bancatest.reconciliation.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bancatest.reconciliation.domain.exception.InvalidAmountException;
import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import com.bancatest.reconciliation.domain.port.in.ReconcilePaymentCommand;
import com.bancatest.reconciliation.domain.port.in.RegisterCoreTransactionCommand;
import com.bancatest.reconciliation.domain.port.out.ProcessedOperationStore;
import com.bancatest.reconciliation.domain.port.out.ReconciliationEventPublisher;
import com.bancatest.reconciliation.domain.port.out.ReconciliationIdProvider;
import com.bancatest.reconciliation.domain.port.out.ReconciliationRepository;
import com.bancatest.reconciliation.domain.port.out.ToleranceProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Idempotency, discards and business validation, exercised through the use cases with
 * hand-written test doubles for the outbound ports.
 *
 * <p>Deliberately not a {@code @QuarkusTest}. Booting the container would require a live
 * datasource, which would make this suite depend on someone's credentials being present. The
 * behaviour under test is in the services, and the services only talk to ports, so doubles
 * exercise it exactly.
 *
 * <p>What this cannot cover is the wiring: that the consumer really hands the command over,
 * and that a discard really acknowledges the Kafka record instead of dead-lettering it. That
 * needs {@code InMemoryConnector} and therefore a configured environment.
 */
class ReconciliationServiceTest {

    private static final Currency PEN = Currency.of("PEN");
    private static final Currency USD = Currency.of("USD");
    private static final Instant OCCURRED = Instant.parse("2026-09-20T10:00:00Z");
    private static final OperationReference OP = OperationReference.of("OP-000123");

    private final InMemoryRepository repository = new InMemoryRepository();
    private final InMemoryProcessedOperations processed = new InMemoryProcessedOperations();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final ReconcilePaymentService.BusinessValidator validator = new ReconcilePaymentService.BusinessValidator();

    private final ReconcilePaymentService reconcilePayment =
            new ReconcilePaymentService(repository, processed, new SequentialIds(), validator);

    private final ToleranceProvider tolerance = () -> Amount.of("1.00", PEN);

    private final RegisterCoreTransactionService registerCoreTransaction =
            new RegisterCoreTransactionService(repository, publisher, validator, tolerance);

    @Nested
    @DisplayName("payment idempotency")
    class PaymentIdempotency {

        @Test
        @DisplayName("the same payment twice produces one reconciliation and no publication")
        void sameEventTwiceHasOneEffect() {
            ReconcilePaymentCommand command = payment("100.00", PEN);

            reconcilePayment.reconcile(command);
            reconcilePayment.reconcile(command);

            assertEquals(1, repository.saveCount, "a duplicate must not be persisted again");
            assertEquals(
                    ReconciliationStatus.PENDING,
                    repository.findByOperationReference(OP).orElseThrow().status());
            assertTrue(publisher.published.isEmpty(), "a PENDING reconciliation publishes nothing");
        }

        @Test
        @DisplayName("a duplicate returns normally, so the record is acknowledged rather than dead-lettered")
        void duplicateIsNotAnError() {
            reconcilePayment.reconcile(payment("100.00", PEN));
            // No exception: this is what keeps the consumer from routing it to the DLQ.
            reconcilePayment.reconcile(payment("100.00", PEN));
        }

        @Test
        @DisplayName("when the fast filter forgets, the repository still stops the duplicate")
        void repositoryIsTheRealGuarantee() {
            reconcilePayment.reconcile(payment("100.00", PEN));
            processed.forgetEverything();

            reconcilePayment.reconcile(payment("100.00", PEN));

            assertEquals(1, repository.saveCount, "the second layer must catch what the first forgot");
        }
    }

    @Nested
    @DisplayName("core transaction discards")
    class CoreTransactionDiscards {

        @Test
        @DisplayName("a transaction for an already resolved reconciliation is discarded, not thrown")
        void resolvedReconciliationIsDiscardedSilently() {
            reconcilePayment.reconcile(payment("100.00", PEN));
            registerCoreTransaction.register(coreTransaction("100.00"));
            publisher.published.clear();

            // The key assertion of the whole design: no IllegalStateTransitionException.
            registerCoreTransaction.register(coreTransaction("95.00"));

            assertEquals(
                    ReconciliationStatus.MATCHED,
                    repository.findByOperationReference(OP).orElseThrow().status(),
                    "state must not change");
            assertTrue(publisher.published.isEmpty(), "nothing is published for a discarded transaction");
        }

        @Test
        @DisplayName("a transaction with no reconciliation is discarded and publishes nothing")
        void orphanIsDiscarded() {
            registerCoreTransaction.register(coreTransaction("100.00"));

            assertTrue(repository.findByOperationReference(OP).isEmpty());
            assertTrue(publisher.published.isEmpty());
        }
    }

    @Nested
    @DisplayName("business validation")
    class Validation {

        @ParameterizedTest(name = "amount {0} {1} is rejected")
        @CsvSource({"0.00,PEN", "0,PEN"})
        @DisplayName("zero is structurally valid but rejected by the business rule")
        void rejectsZero(String amount, String currency) {
            ReconcilePaymentCommand command = payment(amount, Currency.of(currency));
            assertThrows(InvalidAmountException.class, () -> reconcilePayment.reconcile(command));
        }

        @Test
        @DisplayName("a currency other than PEN is rejected")
        void rejectsForeignCurrency() {
            ReconcilePaymentCommand command = payment("100.00", USD);
            assertThrows(InvalidAmountException.class, () -> reconcilePayment.reconcile(command));
        }

        @Test
        @DisplayName("an invalid message leaves no trace and does not stop the next one")
        void invalidInputDoesNotBlockTheStream() {
            assertThrows(InvalidAmountException.class, () -> reconcilePayment.reconcile(payment("0.00", PEN)));
            assertTrue(repository.findByOperationReference(OP).isEmpty(), "nothing was created");

            // A valid message straight afterwards is processed normally.
            reconcilePayment.reconcile(payment("100.00", PEN));
            assertTrue(repository.findByOperationReference(OP).isPresent());
        }
    }

    @Nested
    @DisplayName("resolution and publication")
    class Resolution {

        @ParameterizedTest(name = "core {0} -> {1}")
        @CsvSource({
            "100.00,MATCHED",
            "99.50,MATCHED",
            "99.00,MATCHED",
            "98.99,MISMATCHED",
            "95.00,MISMATCHED",
            "100.50,MISMATCHED"
        })
        @DisplayName("each specification case resolves and publishes to the right channel")
        void resolvesAndPublishes(String coreAmount, String expectedStatus) {
            reconcilePayment.reconcile(payment("100.00", PEN));

            registerCoreTransaction.register(coreTransaction(coreAmount));

            Reconciliation stored = repository.findByOperationReference(OP).orElseThrow();
            assertEquals(ReconciliationStatus.valueOf(expectedStatus), stored.status());
            assertEquals(List.of(expectedStatus), publisher.published);
        }

        @Test
        @DisplayName("publication happens exactly once per resolution")
        void publishesOnce() {
            reconcilePayment.reconcile(payment("100.00", PEN));
            registerCoreTransaction.register(coreTransaction("100.00"));

            assertEquals(1, publisher.published.size());
        }
    }

    private static ReconcilePaymentCommand payment(String amount, Currency currency) {
        return new ReconcilePaymentCommand(OP, AccountNumber.of("1234567890"), Amount.of(amount, currency), OCCURRED);
    }

    private static RegisterCoreTransactionCommand coreTransaction(String amount) {
        return new RegisterCoreTransactionCommand(OP, Amount.of(amount, PEN), OCCURRED);
    }

    // --- test doubles -----------------------------------------------------

    /** Stands in for the database, including its uniqueness guarantee. */
    private static final class InMemoryRepository implements ReconciliationRepository {
        private final Map<String, Reconciliation> stored = new HashMap<>();
        private int saveCount;

        @Override
        public void save(Reconciliation reconciliation) {
            String key = reconciliation.operationReference().value();
            if (!stored.containsKey(key)) {
                saveCount++;
            }
            stored.put(key, reconciliation);
        }

        @Override
        public Optional<Reconciliation> findByOperationReference(OperationReference operationReference) {
            return Optional.ofNullable(stored.get(operationReference.value()));
        }
    }

    /** Stands in for Redis, and can be told to forget, as a TTL expiry would. */
    private static final class InMemoryProcessedOperations implements ProcessedOperationStore {
        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean markAsProcessed(OperationReference operationReference) {
            return seen.add(operationReference.value());
        }

        void forgetEverything() {
            seen.clear();
        }
    }

    private static final class RecordingPublisher implements ReconciliationEventPublisher {
        private final List<String> published = new ArrayList<>();

        @Override
        public void publishMatched(Reconciliation reconciliation) {
            published.add("MATCHED");
        }

        @Override
        public void publishMismatched(Reconciliation reconciliation) {
            published.add("MISMATCHED");
        }
    }

    private static final class SequentialIds implements ReconciliationIdProvider {
        @Override
        public ReconciliationId nextId() {
            return ReconciliationId.of(UUID.randomUUID());
        }
    }
}
