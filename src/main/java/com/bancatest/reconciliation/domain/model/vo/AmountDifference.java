package com.bancatest.reconciliation.domain.model.vo;

import com.bancatest.reconciliation.domain.exception.CurrencyMismatchException;
import com.bancatest.reconciliation.domain.exception.InvalidAmountException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The result of subtracting the core transaction amount from the reported payment amount.
 *
 * <p>Distinct from {@link Amount} for one reason that matters: a difference may be negative.
 * A negative difference means the core charged more than the payment reported, which is
 * always a discrepancy (FR-007). {@code Amount} rejects negatives, so it cannot model this.
 *
 * <p>Sign convention, fixed by FR-005:
 * <ul>
 *   <li>zero - the two figures agree exactly
 *   <li>positive - the core charged less than reported; within tolerance this is an
 *       acceptable fee
 *   <li>negative - the core charged more than reported; never acceptable
 * </ul>
 *
 * <p>Like {@code Amount}, normalised to scale 2 with an explicit rounding mode, and compared
 * through {@code compareTo} rather than {@link BigDecimal#equals}.
 */
public record AmountDifference(BigDecimal value, Currency currency) {

    public AmountDifference {
        if (value == null) {
            throw new InvalidAmountException("Difference value must not be null");
        }
        if (currency == null) {
            throw new InvalidAmountException("Difference currency must not be null");
        }
        value = value.setScale(Amount.SCALE, Amount.ROUNDING);
    }

    public static AmountDifference of(String value, Currency currency) {
        Objects.requireNonNull(value, "Difference text must not be null");
        return new AmountDifference(new BigDecimal(value), currency);
    }

    /**
     * Whether this difference is acceptable given a fixed tolerance.
     *
     * <p>Acceptable means zero, or positive and no greater than the tolerance. The bound is
     * inclusive: a difference exactly equal to the tolerance is still acceptable. Any
     * negative difference is rejected regardless of magnitude.
     *
     * <p>Computed by direct comparison, never by division: the tolerance is a fixed amount,
     * not a percentage.
     *
     * @throws CurrencyMismatchException if the tolerance is expressed in another currency
     */
    public boolean isWithinTolerance(Amount tolerance) {
        Objects.requireNonNull(tolerance, "Tolerance must not be null");
        if (!currency.equals(tolerance.currency())) {
            throw new CurrencyMismatchException(
                    currency.code(), tolerance.currency().code());
        }
        if (isNegative()) {
            return false;
        }
        return value.compareTo(tolerance.value()) <= 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AmountDifference other)) {
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
