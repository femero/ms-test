package com.bancatest.reconciliation.domain.model.vo;

/**
 * The business identifier that links a reported payment to its core transaction.
 *
 * <p>It is the single idempotency criterion of the whole service (FR-018): the unique index
 * in the database and the Redis pre-filter both key on this value, and nothing else. The
 * event identifier carried by incoming messages is informational and must never be used to
 * decide whether something was already processed.
 *
 * <p>Validates that the value is present and not blank. A blank reference cannot be linked
 * to anything, so a message carrying one is invalid (FR-022) rather than merely unusual.
 */
public record OperationReference(String value) {

    private static final int MAX_LENGTH = 64;

    public OperationReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OperationReference must not be null or blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "OperationReference must not exceed " + MAX_LENGTH + " characters, got: " + value.length());
        }
    }

    public static OperationReference of(String value) {
        return new OperationReference(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
