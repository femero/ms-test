package com.bancatest.reconciliation.domain.model.vo;

/**
 * The account a reported payment was made against.
 *
 * <p>Informational: no reconciliation rule reads it. It is kept because it travels in the
 * contract and is useful when investigating an incident.
 *
 * <p>Validates presence and a 34-character ceiling, the maximum length of an IBAN. The
 * specification does not fix an account length, so the widest figure in the standard is
 * used as the bound.
 *
 * <p>Never log this value in full.
 */
public record AccountNumber(String value) {

    private static final int MAX_LENGTH = 34;

    public AccountNumber {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AccountNumber must not be null or blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "AccountNumber must not exceed " + MAX_LENGTH + " characters, got: " + value.length());
        }
    }

    public static AccountNumber of(String value) {
        return new AccountNumber(value);
    }

    /**
     * Last four characters, for logs and diagnostics. Logging the full number is forbidden.
     */
    public String masked() {
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }
}
