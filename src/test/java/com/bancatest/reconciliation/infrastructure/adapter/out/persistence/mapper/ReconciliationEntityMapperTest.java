package com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.entity.ReconciliationEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round-trip test for the persistence mapper: domain to entity and back must preserve every
 * field, in both the PENDING and the resolved shapes.
 *
 * <p>Plain JUnit; the mapper is a pure function and needs no CDI container.
 */
class ReconciliationEntityMapperTest {

    private static final Currency PEN = Currency.of("PEN");
    private static final Instant CREATED = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant RESOLVED = Instant.parse("2026-09-20T10:05:00Z");

    private final ReconciliationEntityMapper mapper = new ReconciliationEntityMapperImpl();

    @Test
    @DisplayName("a PENDING reconciliation survives the round trip with its optionals empty")
    void pendingRoundTrip() {
        Reconciliation original = pending();

        Reconciliation restored = mapper.toDomain(mapper.toEntity(original));

        assertEquals(original.id(), restored.id());
        assertEquals(original.operationReference(), restored.operationReference());
        assertEquals(original.accountNumber(), restored.accountNumber());
        assertEquals(original.paymentAmount(), restored.paymentAmount());
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(ReconciliationStatus.PENDING, restored.status());
        assertTrue(restored.coreAmount().isEmpty());
        assertTrue(restored.difference().isEmpty());
        assertTrue(restored.resolvedAt().isEmpty());
    }

    @Test
    @DisplayName("a MATCHED reconciliation survives the round trip with every field populated")
    void matchedRoundTrip() {
        Reconciliation original = pending();
        Amount core = Amount.of("99.50", PEN);
        AmountDifference difference = original.paymentAmount().subtract(core);
        original.match(core, difference, RESOLVED);

        Reconciliation restored = mapper.toDomain(mapper.toEntity(original));

        assertEquals(ReconciliationStatus.MATCHED, restored.status());
        assertEquals(core, restored.coreAmount().orElseThrow());
        assertEquals(difference, restored.difference().orElseThrow());
        assertEquals(RESOLVED, restored.resolvedAt().orElseThrow());
    }

    @Test
    @DisplayName("a negative difference survives the round trip, sign included")
    void negativeDifferenceRoundTrip() {
        Reconciliation original = pending();
        Amount core = Amount.of("100.50", PEN);
        AmountDifference difference = original.paymentAmount().subtract(core);
        original.markAsMismatched(core, difference, RESOLVED);

        Reconciliation restored = mapper.toDomain(mapper.toEntity(original));

        assertEquals(ReconciliationStatus.MISMATCHED, restored.status());
        assertEquals(AmountDifference.of("-0.50", PEN), restored.difference().orElseThrow());
        assertTrue(restored.difference().orElseThrow().isNegative());
    }

    @Test
    @DisplayName("applyToEntity updates a row in place instead of replacing it")
    void applyToEntityMutatesInPlace() {
        Reconciliation reconciliation = pending();
        ReconciliationEntity entity = mapper.toEntity(reconciliation);
        assertEquals(ReconciliationStatus.PENDING, entity.getStatus());

        Amount core = Amount.of("100.00", PEN);
        reconciliation.match(core, reconciliation.paymentAmount().subtract(core), RESOLVED);
        mapper.applyToEntity(reconciliation, entity);

        assertEquals(ReconciliationStatus.MATCHED, entity.getStatus());
        assertEquals(RESOLVED, entity.getResolvedAt());
        assertEquals("PEN", entity.getDifferenceCurrency());
    }

    private static Reconciliation pending() {
        return Reconciliation.start(
                ReconciliationId.of(UUID.randomUUID()),
                OperationReference.of("OP-000123"),
                AccountNumber.of("1234567890"),
                Amount.of("100.00", PEN),
                CREATED);
    }
}
