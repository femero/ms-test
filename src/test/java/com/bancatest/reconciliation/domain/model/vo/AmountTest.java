package com.bancatest.reconciliation.domain.model.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bancatest.reconciliation.domain.exception.CurrencyMismatchException;
import com.bancatest.reconciliation.domain.exception.InvalidAmountException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The equality and currency rules that the constitution names explicitly. */
class AmountTest {

    private static final Currency PEN = Currency.of("PEN");
    private static final Currency USD = Currency.of("USD");

    @Test
    @DisplayName("120.0 and 120.00 are the same amount")
    void scaleDoesNotAffectEquality() {
        Amount a = Amount.of("120.0", PEN);
        Amount b = Amount.of("120.00", PEN);

        assertEquals(a, b, "equality must compare by value, not by scale");
        assertEquals(a.hashCode(), b.hashCode(), "equal amounts must share a hash code");
    }

    @Test
    @DisplayName("BigDecimal.equals would have failed this, which is why equals is overridden")
    void rawBigDecimalEqualsWouldDisagree() {
        assertNotEquals(new BigDecimal("120.0"), new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("same figure in different currencies is not the same amount")
    void currencyIsPartOfIdentity() {
        assertNotEquals(Amount.of("120.00", PEN), Amount.of("120.00", USD));
    }

    @Test
    @DisplayName("subtracting across currencies fails instead of producing a number")
    void subtractAcrossCurrenciesThrows() {
        Amount pen = Amount.of("100.00", PEN);
        Amount usd = Amount.of("100.00", USD);

        assertThrows(CurrencyMismatchException.class, () -> pen.subtract(usd));
    }

    @Test
    @DisplayName("comparing a difference against a tolerance in another currency fails")
    void toleranceAcrossCurrenciesThrows() {
        AmountDifference difference = AmountDifference.of("0.50", PEN);
        Amount toleranceInUsd = Amount.of("1.00", USD);

        assertThrows(CurrencyMismatchException.class, () -> difference.isWithinTolerance(toleranceInUsd));
    }

    @Test
    @DisplayName("amounts normalise to scale 2 with HALF_UP")
    void normalisesToScaleTwo() {
        assertEquals("120.00", Amount.of("120", PEN).value().toPlainString());
        assertEquals("0.13", Amount.of("0.125", PEN).value().toPlainString());
        assertEquals("0.12", Amount.of("0.124", PEN).value().toPlainString());
    }

    @Test
    @DisplayName("an amount cannot be negative, null, or without currency")
    void constructionInvariants() {
        assertThrows(InvalidAmountException.class, () -> Amount.of("-0.01", PEN));
        assertThrows(InvalidAmountException.class, () -> new Amount(null, PEN));
        assertThrows(InvalidAmountException.class, () -> new Amount(new BigDecimal("1.00"), null));
    }

    @Test
    @DisplayName("zero is a valid amount structurally; rejecting it is a business rule")
    void zeroIsStructurallyValid() {
        Amount zero = Amount.of("0.00", PEN);
        assertTrue(zero.isZero());
        assertFalse(zero.isPositive());
    }

    @Test
    @DisplayName("subtraction yields the difference with the sign convention of FR-005")
    void subtractionSign() {
        Amount payment = Amount.of("100.00", PEN);

        assertEquals(AmountDifference.of("0.50", PEN), payment.subtract(Amount.of("99.50", PEN)));
        assertEquals(AmountDifference.of("0.00", PEN), payment.subtract(Amount.of("100.00", PEN)));

        AmountDifference negative = payment.subtract(Amount.of("100.50", PEN));
        assertEquals(AmountDifference.of("-0.50", PEN), negative);
        assertTrue(negative.isNegative(), "core charging more must yield a negative difference");
    }
}
