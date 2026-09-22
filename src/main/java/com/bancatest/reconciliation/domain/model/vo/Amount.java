package com.bancatest.reconciliation.domain.model.vo;

import com.bancatest.reconciliation.domain.exception.CurrencyMismatchException;
import com.bancatest.reconciliation.domain.exception.InvalidAmountException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A monetary quantity: a figure that always travels with its currency.
 *
 * <p>Invariants guaranteed on construction: the figure is present, not negative, and the
 * currency is present. An amount that breaks any of these does not exist.
 *
 * <p>Every instance is normalised to scale 2 with an explicit {@link RoundingMode#HALF_UP}.
 * Leaving rounding implicit is what turns a cent into a reconciliation discrepancy nobody
 * can explain.
 *
 * <p>{@code equals} and {@code hashCode} are overridden deliberately. The record default
 * would delegate to {@link BigDecimal#equals}, which distinguishes {@code 120.0} from
 * {@code 120.00} because it compares scale as well as value. Normalisation already makes
 * that unlikely, but relying on it would leave a trap for anyone who later changes the
 * scale. Comparison goes through {@code compareTo}, always.
 *
 * <p>Negative figures are rejected here on purpose. A difference may legitimately be
 * negative when the core charged more than the payment reported, and that concept is
 * modelled by {@link AmountDifference}, not by this type.
 */
public record Amount(BigDecimal value, Currency currency) {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public Amount {
        if (value == null) {
            throw new InvalidAmountException("Amount value must not be null");
        }
        if (currency == null) {
            throw new InvalidAmountException("Amount currency must not be null");
        }
        if (value.signum() < 0) {
            throw new InvalidAmountException("Amount must not be negative, got: " + value);
        }
        value = value.setScale(SCALE, ROUNDING);
    }

    /**
     * Builds an amount from its textual representation. This is the safe entry point:
     * {@code new BigDecimal(double)} is forbidden because it carries the binary
     * representation error this whole chain exists to avoid.
     */
    public static Amount of(String value, Currency currency) {
        Objects.requireNonNull(value, "Amount text must not be null");
        return new Amount(new BigDecimal(value), currency);
    }

    public static Amount of(BigDecimal value, Currency currency) {
        return new Amount(value, currency);
    }

    /**
     * Subtracts another amount from this one, yielding a difference that may be negative.
     *
     * @throws CurrencyMismatchException if the currencies differ; the domain never converts
     *     nor compares across currencies
     */
    public AmountDifference subtract(Amount other) {
        Objects.requireNonNull(other, "Cannot subtract a null amount");
        requireSameCurrency(other.currency);
        return new AmountDifference(this.value.subtract(other.value), this.currency);
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    private void requireSameCurrency(Currency other) {
        if (!this.currency.equals(other)) {
            throw new CurrencyMismatchException(this.currency.code(), other.code());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Amount other)) {
            return false;
        }
        return currency.equals(other.currency) && value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return value.toPlainString() + " " + currency.code();
    }
}
