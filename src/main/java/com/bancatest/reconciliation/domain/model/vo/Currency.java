package com.bancatest.reconciliation.domain.model.vo;

import java.util.regex.Pattern;

/**
 * An ISO-4217 currency code.
 *
 * <p>Validates that the code is exactly three uppercase letters. Whether a given currency is
 * acceptable to the business is not this type's concern: the current scope only reconciles
 * PEN, but that is a use-case rule, not a structural one. This type only guarantees that a
 * currency is well formed, so nothing downstream has to re-check its shape.
 */
public record Currency(String code) {

    private static final Pattern ISO_4217 = Pattern.compile("^[A-Z]{3}$");

    public Currency {
        if (code == null || !ISO_4217.matcher(code).matches()) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO-4217 code, got: " + code);
        }
    }

    public static Currency of(String code) {
        return new Currency(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
