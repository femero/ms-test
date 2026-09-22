package com.bancatest.reconciliation.domain.exception;

/**
 * Raised when an amount violates its construction invariants: null value, null currency,
 * or a negative figure.
 *
 * <p>Unchecked on purpose. Checked exceptions are forbidden in the domain, and an invalid
 * amount is a programming or integration error, never a recoverable business outcome.
 */
public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(String message) {
        super(message);
    }
}
