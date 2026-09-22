package com.bancatest.reconciliation.domain.model.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Construction-time validation for every Value Object: if it is invalid, it must not exist.
 *
 * <p>Amount has its own suite, since the constitution calls out its equality rules
 * specifically.
 */
class ValueObjectValidationTest {

    @Nested
    @DisplayName("Currency")
    class CurrencyValidation {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"PE", "PENN", "pen", "P3N", "  ", "PE "})
        @DisplayName("rejects anything that is not three uppercase letters")
        void rejectsMalformedCodes(String code) {
            assertThrows(IllegalArgumentException.class, () -> Currency.of(code));
        }

        @Test
        @DisplayName("accepts a well-formed ISO-4217 code")
        void acceptsValidCode() {
            assertEquals("PEN", Currency.of("PEN").code());
        }
    }

    @Nested
    @DisplayName("OperationReference")
    class OperationReferenceValidation {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t"})
        @DisplayName("rejects absent or blank references")
        void rejectsBlank(String value) {
            assertThrows(IllegalArgumentException.class, () -> OperationReference.of(value));
        }

        @Test
        @DisplayName("rejects a reference longer than the 64-character column")
        void rejectsOverlongReference() {
            String tooLong = "X".repeat(65);
            assertThrows(IllegalArgumentException.class, () -> OperationReference.of(tooLong));
        }

        @Test
        @DisplayName("accepts a normal reference")
        void acceptsValidReference() {
            assertEquals("OP-000123", OperationReference.of("OP-000123").value());
        }
    }

    @Nested
    @DisplayName("AccountNumber")
    class AccountNumberValidation {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "  "})
        @DisplayName("rejects absent or blank account numbers")
        void rejectsBlank(String value) {
            assertThrows(IllegalArgumentException.class, () -> AccountNumber.of(value));
        }

        @Test
        @DisplayName("rejects an account longer than the IBAN maximum of 34")
        void rejectsOverlongAccount() {
            assertThrows(IllegalArgumentException.class, () -> AccountNumber.of("1".repeat(35)));
        }

        @Test
        @DisplayName("masks all but the last four characters, so logs never carry the full number")
        void masksForLogging() {
            assertEquals("****7890", AccountNumber.of("1234567890").masked());
            assertEquals("****", AccountNumber.of("123").masked());
        }
    }

    @Nested
    @DisplayName("ReconciliationId")
    class ReconciliationIdValidation {

        @Test
        @DisplayName("rejects a null identifier")
        void rejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> ReconciliationId.of(null));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "not-a-uuid"})
        @DisplayName("rejects text that is not a UUID")
        void rejectsMalformedText(String value) {
            assertThrows(IllegalArgumentException.class, () -> ReconciliationId.fromString(value));
        }

        @Test
        @DisplayName("accepts a UUID")
        void acceptsUuid() {
            UUID uuid = UUID.randomUUID();
            assertEquals(uuid, ReconciliationId.of(uuid).value());
        }
    }

    @Nested
    @DisplayName("AmountDifference")
    class AmountDifferenceValidation {

        private static final Currency PEN = Currency.of("PEN");

        @Test
        @DisplayName("rejects a null figure or a null currency")
        void rejectsNulls() {
            assertThrows(RuntimeException.class, () -> new AmountDifference(null, PEN));
            assertThrows(RuntimeException.class, () -> AmountDifference.of("1.00", null));
        }

        @Test
        @DisplayName("accepts negatives, unlike Amount: the core may charge more")
        void acceptsNegative() {
            assertEquals("-0.50", AmountDifference.of("-0.50", PEN).value().toPlainString());
        }
    }
}
