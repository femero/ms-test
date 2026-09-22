package com.bancatest.reconciliation.infrastructure.adapter.in.kafka;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import com.bancatest.reconciliation.infrastructure.InMemoryChannels;
import com.bancatest.reconciliation.infrastructure.InMemoryReconciliationRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.Record;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.com.bancatest.banking.events.CoreTransactionReported;
import pe.com.bancatest.payments.events.PaymentExecuted;
import pe.com.bancatest.reconciliation.events.DiscrepancyDetected;
import pe.com.bancatest.reconciliation.events.ReconciliationMatched;

/**
 * End-to-end wiring through the real consumers, mappers, use cases and publisher, with the
 * Kafka transport swapped for SmallRye's in-memory connector.
 *
 * <p>This covers what the service-level suite deliberately cannot: that a record really
 * reaches the use case, that a resolution really emits on the right channel, and that a
 * discard really completes normally instead of failing the message.
 *
 * <p>Requires no database, no Redis and no broker: the test profile switches the datasource
 * off and replaces the two outbound ports with in-memory doubles.
 */
@QuarkusTest
@QuarkusTestResource(InMemoryChannels.class)
class ConsumerWiringTest {

    private static final Instant OCCURRED = Instant.parse("2026-09-20T10:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // Asking the connector for its channels, rather than injecting
    // InMemorySource/InMemorySink directly: those are produced per identifier at runtime,
    // so Quarkus cannot resolve them at build time and the deployment fails.
    @Inject
    @Any
    InMemoryConnector connector;

    @Inject
    InMemoryReconciliationRepository.Repository repository;

    @Inject
    InMemoryReconciliationRepository.ProcessedOperations processedOperations;

    private InMemorySource<PaymentExecuted> payments;
    private InMemorySource<CoreTransactionReported> coreTransactions;
    private InMemorySink<Record<String, ReconciliationMatched>> matched;
    private InMemorySink<Record<String, DiscrepancyDetected>> discrepancies;

    @BeforeEach
    void reset() {
        payments = connector.source("payments-in");
        coreTransactions = connector.source("core-transactions-in");
        matched = connector.sink("results-out");
        discrepancies = connector.sink("discrepancies-out");

        repository.clear();
        processedOperations.clear();
        matched.clear();
        discrepancies.clear();
    }

    @Test
    @DisplayName("a payment creates a PENDING reconciliation and publishes nothing")
    void paymentCreatesPending() {
        payments.send(payment("OP-1", "100.00"));

        await().atMost(TIMEOUT).until(() -> repository.size() == 1);

        assertEquals(
                ReconciliationStatus.PENDING,
                repository
                        .findByOperationReference(OperationReference.of("OP-1"))
                        .orElseThrow()
                        .status());
        assertTrue(matched.received().isEmpty());
        assertTrue(discrepancies.received().isEmpty());
    }

    @Test
    @DisplayName("a matching core transaction resolves and emits on the conforming channel")
    void matchingCoreTransactionPublishesToResults() {
        payments.send(payment("OP-2", "100.00"));
        await().atMost(TIMEOUT).until(() -> repository.size() == 1);

        coreTransactions.send(coreTransaction("OP-2", "99.50"));

        await().atMost(TIMEOUT).until(() -> !matched.received().isEmpty());
        assertTrue(discrepancies.received().isEmpty(), "a match must not reach the discrepancy channel");
        assertEquals(
                new BigDecimal("0.50"),
                matched.received().get(0).getPayload().value().getDifference());
    }

    @Test
    @DisplayName("a difference beyond tolerance emits on the discrepancy channel")
    void mismatchPublishesToDiscrepancies() {
        payments.send(payment("OP-3", "100.00"));
        await().atMost(TIMEOUT).until(() -> repository.size() == 1);

        coreTransactions.send(coreTransaction("OP-3", "95.00"));

        await().atMost(TIMEOUT).until(() -> !discrepancies.received().isEmpty());
        assertTrue(matched.received().isEmpty());
        assertEquals(
                new BigDecimal("5.00"),
                discrepancies.received().get(0).getPayload().value().getDifference());
    }

    @Test
    @DisplayName("the same payment twice yields one reconciliation, and the duplicate is acknowledged")
    void duplicatePaymentIsAbsorbed() {
        payments.send(payment("OP-4", "100.00"));
        await().atMost(TIMEOUT).until(() -> repository.size() == 1);

        payments.send(payment("OP-4", "100.00"));

        // Still one reconciliation after the redelivery, and the stream keeps running.
        await().during(Duration.ofMillis(300)).atMost(TIMEOUT).until(() -> repository.size() == 1);

        payments.send(payment("OP-4b", "100.00"));
        await().atMost(TIMEOUT).until(() -> repository.size() == 2);
    }

    @Test
    @DisplayName("a core transaction for a resolved reconciliation is discarded without a second publication")
    void coreTransactionOnResolvedIsDiscarded() {
        payments.send(payment("OP-5", "100.00"));
        await().atMost(TIMEOUT).until(() -> repository.size() == 1);
        coreTransactions.send(coreTransaction("OP-5", "100.00"));
        await().atMost(TIMEOUT).until(() -> !matched.received().isEmpty());

        coreTransactions.send(coreTransaction("OP-5", "50.00"));

        await().during(Duration.ofMillis(300))
                .atMost(TIMEOUT)
                .until(() -> matched.received().size() == 1);
        assertTrue(discrepancies.received().isEmpty(), "a discarded transaction publishes nothing");
        assertEquals(
                ReconciliationStatus.MATCHED,
                repository
                        .findByOperationReference(OperationReference.of("OP-5"))
                        .orElseThrow()
                        .status(),
                "state must not change");
    }

    @Test
    @DisplayName("an orphan core transaction is discarded and does not stop the stream")
    void orphanIsDiscardedAndStreamContinues() {
        coreTransactions.send(coreTransaction("OP-404", "100.00"));

        payments.send(payment("OP-6", "100.00"));
        await().atMost(TIMEOUT).until(() -> repository.size() == 1);

        assertTrue(matched.received().isEmpty());
        assertTrue(discrepancies.received().isEmpty());
    }

    private static PaymentExecuted payment(String reference, String amount) {
        return PaymentExecuted.newBuilder()
                .setEventId("evt-" + reference)
                .setOperationReference(reference)
                .setAccountNumber("1234567890")
                .setAmount(new BigDecimal(amount))
                .setCurrency("PEN")
                .setOccurredAt(OCCURRED)
                .build();
    }

    private static CoreTransactionReported coreTransaction(String reference, String amount) {
        return CoreTransactionReported.newBuilder()
                .setEventId("evt-core-" + reference)
                .setOperationReference(reference)
                .setAmount(new BigDecimal(amount))
                .setCurrency("PEN")
                .setOccurredAt(OCCURRED)
                .build();
    }
}
