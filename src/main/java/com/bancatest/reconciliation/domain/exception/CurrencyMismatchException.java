package com.bancatest.reconciliation.domain.exception;

/**
 * Raised when two amounts in different currencies are compared or operated on.
 *
 * <p>The domain never converts between currencies and never falls back to comparing raw
 * figures: doing so would silently produce a reconciliation result computed from
 * incomparable quantities.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String left, String right) {
        super("Cannot operate on amounts in different currencies: " + left + " and " + right);
    }
}
