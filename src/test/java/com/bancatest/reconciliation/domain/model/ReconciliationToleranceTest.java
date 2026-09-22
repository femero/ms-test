package com.bancatest.reconciliation.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bancatest.reconciliation.domain.exception.IllegalStateTransitionException;
import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The seven amount cases from the specification (SC-002), exercised against the aggregate.
 *
 * <p>Two of them carry the weight: 99.00 sits exactly on the tolerance and must match, and
 * 98.99 is one cent past it and must not. Together they pin the boundary as inclusive.
 * 100.50 pins the other rule: any negative difference is a discrepancy, however small.
 */
class ReconciliationToleranceTest {

    private static final Currency PEN = Currency.of("PEN");
    private static final Amount TOLERANCE = Amount.of("1.00", PEN);
    private static final Instant NOW = Instant.parse("2026-09-20T10:15:30Z");

    static Stream<Arguments> specificationCases() {
        return Stream.of(
                Arguments.of("100.00", "100.00", "0.00", ReconciliationStatus.MATCHED),
                Arguments.of("100.00", "99.50", "0.50", ReconciliationStatus.MATCHED),
                Arguments.of("100.00", "99.00", "1.00", ReconciliationStatus.MATCHED),
                Arguments.of("100.00", "98.99", "1.01", ReconciliationStatus.MISMATCHED),
                Arguments.of("100.00", "95.00", "5.00", ReconciliationStatus.MISMATCHED),
                Arguments.of("100.00", "100.50", "-0.50", ReconciliationStatus.MISMATCHED));
    }

    @ParameterizedTest(name = "payment {0} vs core {1} -> difference {2}, {3}")
    @MethodSource("specificationCases")
    @DisplayName("each specification case yields the declared difference and status")
    void specificationCasesHold(String payment, String core, String expectedDifference, ReconciliationStatus expected) {

        Reconciliation reconciliation = pending(payment);
        Amount coreAmount = Amount.of(core, PEN);
        AmountDifference difference = reconciliation.paymentAmount().subtract(coreAmount);

        assertEquals(AmountDifference.of(expectedDifference, PEN), difference, "difference figure");

        if (difference.isWithinTolerance(TOLERANCE)) {
            reconciliation.match(coreAmount, difference, NOW);
        } else {
            reconciliation.markAsMismatched(coreAmount, difference, NOW);
        }

        assertEquals(expected, reconciliation.status());
        assertEquals(difference, reconciliation.difference().orElseThrow());
        assertEquals(coreAmount, reconciliation.coreAmount().orElseThrow());
        assertEquals(NOW, reconciliation.resolvedAt().orElseThrow());
    }

    @Test
    @DisplayName("the tolerance bound is inclusive: exactly 1.00 still matches")
    void toleranceBoundIsInclusive() {
        assertTrue(AmountDifference.of("1.00", PEN).isWithinTolerance(TOLERANCE));
        assertFalse(AmountDifference.of("1.01", PEN).isWithinTolerance(TOLERANCE));
    }

    @Test
    @DisplayName("any negative difference is out of tolerance, however small")
    void negativeIsNeverWithinTolerance() {
        assertFalse(AmountDifference.of("-0.01", PEN).isWithinTolerance(TOLERANCE));
        assertFalse(AmountDifference.of("-0.50", PEN).isWithinTolerance(TOLERANCE));
    }

    @Test
    @DisplayName("a new reconciliation starts PENDING with nothing resolved")
    void startsPending() {
        Reconciliation reconciliation = pending("100.00");

        assertEquals(ReconciliationStatus.PENDING, reconciliation.status());
        assertFalse(reconciliation.isResolved());
        assertTrue(reconciliation.coreAmount().isEmpty());
        assertTrue(reconciliation.difference().isEmpty());
        assertTrue(reconciliation.resolvedAt().isEmpty());
    }

    @Test
    @DisplayName("a resolved reconciliation refuses a second resolution")
    void resolvingTwiceIsRejected() {
        Reconciliation reconciliation = pending("100.00");
        Amount core = Amount.of("100.00", PEN);
        AmountDifference zero = AmountDifference.of("0.00", PEN);

        reconciliation.match(core, zero, NOW);
        assertTrue(reconciliation.isResolved());

        IllegalStateTransitionException thrown = assertThrows(
                IllegalStateTransitionException.class, () -> reconciliation.markAsMismatched(core, zero, NOW));

        assertEquals(ReconciliationStatus.MATCHED, thrown.from());
        assertEquals(ReconciliationStatus.MISMATCHED, thrown.to());
    }

    private static Reconciliation pending(String paymentAmount) {
        return Reconciliation.start(
                ReconciliationId.of(UUID.randomUUID()),
                OperationReference.of("OP-000123"),
                AccountNumber.of("1234567890"),
                Amount.of(paymentAmount, PEN),
                NOW);
    }
}
