package com.bancatest.reconciliation.infrastructure.adapter.out.messaging.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pe.com.bancatest.reconciliation.events.DiscrepancyDetected;
import pe.com.bancatest.reconciliation.events.ReconciliationMatched;

/** Round-trip tests for the outbound Avro mapper: aggregate to published contract. */
class ReconciliationEventMapperTest {

    private static final Currency PEN = Currency.of("PEN");
    private static final Instant CREATED = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant RESOLVED = Instant.parse("2026-09-20T10:05:00Z");

    private final ReconciliationEventMapper mapper = new ReconciliationEventMapperImpl();

    @Test
    @DisplayName("a matched reconciliation maps every field of the conforming contract")
    void mapsMatched() {
        Reconciliation reconciliation = resolved("99.50", true);

        ReconciliationMatched event = mapper.toMatched(reconciliation);

        assertEquals(reconciliation.id().value().toString(), event.getReconciliationId());
        assertEquals("OP-000123", event.getOperationReference());
        assertEquals(new BigDecimal("100.00"), event.getAmount());
        assertEquals("PEN", event.getCurrency());
        assertEquals(new BigDecimal("0.50"), event.getDifference());
        assertEquals(RESOLVED, event.getReconciledAt());
        assertTrue(event.getEventId() != null && !event.getEventId().isBlank());
    }

    @Test
    @DisplayName("a discrepancy maps both amounts plus the difference")
    void mapsDiscrepancy() {
        Reconciliation reconciliation = resolved("95.00", false);

        DiscrepancyDetected event = mapper.toDiscrepancy(reconciliation);

        assertEquals(new BigDecimal("100.00"), event.getPaymentAmount());
        assertEquals(new BigDecimal("95.00"), event.getCoreAmount());
        assertEquals(new BigDecimal("5.00"), event.getDifference());
        assertEquals("PEN", event.getCurrency());
        assertEquals(RESOLVED, event.getDetectedAt());
    }

    @Test
    @DisplayName("a negative difference keeps its sign on the wire")
    void negativeDifferenceSurvives() {
        Reconciliation reconciliation = resolved("100.50", false);

        DiscrepancyDetected event = mapper.toDiscrepancy(reconciliation);

        assertEquals(new BigDecimal("-0.50"), event.getDifference());
    }

    @Test
    @DisplayName("each publication carries its own event id, distinct from the reconciliation id")
    void eventIdIsPerPublication() {
        Reconciliation reconciliation = resolved("99.50", true);

        ReconciliationMatched first = mapper.toMatched(reconciliation);
        ReconciliationMatched second = mapper.toMatched(reconciliation);

        assertNotEquals(first.getEventId(), second.getEventId());
        assertNotEquals(first.getEventId(), first.getReconciliationId());
    }

    @Test
    @DisplayName("mapping an unresolved reconciliation fails loudly instead of emitting nulls")
    void refusesUnresolved() {
        Reconciliation pending = pending();
        assertThrows(NoSuchElementException.class, () -> mapper.toMatched(pending));
    }

    private static Reconciliation pending() {
        return Reconciliation.start(
                ReconciliationId.of(UUID.randomUUID()),
                OperationReference.of("OP-000123"),
                AccountNumber.of("1234567890"),
                Amount.of("100.00", PEN),
                CREATED);
    }

    private static Reconciliation resolved(String coreAmount, boolean matched) {
        Reconciliation reconciliation = pending();
        Amount core = Amount.of(coreAmount, PEN);
        AmountDifference difference = reconciliation.paymentAmount().subtract(core);
        if (matched) {
            reconciliation.match(core, difference, RESOLVED);
        } else {
            reconciliation.markAsMismatched(core, difference, RESOLVED);
        }
        return reconciliation;
    }
}
