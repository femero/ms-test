package com.bancatest.reconciliation.infrastructure.adapter.in.kafka.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bancatest.reconciliation.domain.exception.InvalidAmountException;
import com.bancatest.reconciliation.domain.port.in.ReconcilePaymentCommand;
import com.bancatest.reconciliation.domain.port.in.RegisterCoreTransactionCommand;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pe.com.bancatest.banking.events.CoreTransactionReported;
import pe.com.bancatest.payments.events.PaymentExecuted;

/**
 * Round-trip tests for the two inbound Avro mappers.
 *
 * <p>Round trip here means contract to command and back to the raw figures, since the
 * command is the inward end of the translation and nothing maps back to Avro on this side.
 *
 * <p>These also pin the property R-12 warned about: the generated {@code amount} field must
 * be a {@link BigDecimal}. If the decimal logical type were ever switched off it would come
 * through as a {@code ByteBuffer} and this class would stop compiling, which is the point.
 */
class AvroInboundMapperTest {

    private static final Instant OCCURRED = Instant.parse("2026-09-20T10:00:00Z");

    @Nested
    @DisplayName("PaymentAvroMapper")
    class PaymentMapping {

        private final PaymentAvroMapper mapper = new PaymentAvroMapperImpl();

        @Test
        @DisplayName("every contract field reaches the command with its value intact")
        void mapsAllFields() {
            PaymentExecuted event = payment("OP-000123", "1234567890", new BigDecimal("100.00"), "PEN");

            ReconcilePaymentCommand command = mapper.toCommand(event);

            assertEquals("OP-000123", command.operationReference().value());
            assertEquals("1234567890", command.accountNumber().value());
            assertEquals(new BigDecimal("100.00"), command.amount().value());
            assertEquals("PEN", command.amount().currency().code());
            assertEquals(OCCURRED, command.occurredAt());
        }

        @Test
        @DisplayName("the event id is deliberately dropped: idempotency keys on the operation reference")
        void doesNotCarryEventId() {
            // The command record has exactly four components and none of them is the event id.
            assertEquals(4, ReconcilePaymentCommand.class.getRecordComponents().length);
            assertTrue(java.util.Arrays.stream(ReconcilePaymentCommand.class.getRecordComponents())
                    .noneMatch(c -> c.getName().toLowerCase().contains("event")));
        }

        @Test
        @DisplayName("a blank operation reference is rejected at the boundary, not carried inwards")
        void rejectsBlankReference() {
            PaymentExecuted event = payment("", "1234567890", new BigDecimal("100.00"), "PEN");
            assertThrows(IllegalArgumentException.class, () -> mapper.toCommand(event));
        }

        @Test
        @DisplayName("a negative amount is rejected at the boundary")
        void rejectsNegativeAmount() {
            PaymentExecuted event = payment("OP-1", "1234567890", new BigDecimal("-1.00"), "PEN");
            assertThrows(InvalidAmountException.class, () -> mapper.toCommand(event));
        }

        private PaymentExecuted payment(String reference, String account, BigDecimal amount, String currency) {
            return PaymentExecuted.newBuilder()
                    .setEventId("evt-1")
                    .setOperationReference(reference)
                    .setAccountNumber(account)
                    .setAmount(amount)
                    .setCurrency(currency)
                    .setOccurredAt(OCCURRED)
                    .build();
        }
    }

    @Nested
    @DisplayName("CoreTransactionAvroMapper")
    class CoreTransactionMapping {

        private final CoreTransactionAvroMapper mapper = new CoreTransactionAvroMapperImpl();

        @Test
        @DisplayName("every contract field reaches the command with its value intact")
        void mapsAllFields() {
            CoreTransactionReported event = coreTransaction("OP-000123", new BigDecimal("99.50"), "PEN");

            RegisterCoreTransactionCommand command = mapper.toCommand(event);

            assertEquals("OP-000123", command.operationReference().value());
            assertEquals(new BigDecimal("99.50"), command.amount().value());
            assertEquals("PEN", command.amount().currency().code());
            assertEquals(OCCURRED, command.occurredAt());
        }

        @Test
        @DisplayName("scale is normalised to 2 on the way in")
        void normalisesScale() {
            RegisterCoreTransactionCommand command =
                    mapper.toCommand(coreTransaction("OP-1", new BigDecimal("99.5"), "PEN"));

            assertEquals("99.50", command.amount().value().toPlainString());
        }

        @Test
        @DisplayName("a currency that is not a valid ISO code is rejected at the boundary")
        void rejectsMalformedCurrency() {
            CoreTransactionReported event = coreTransaction("OP-1", new BigDecimal("10.00"), "pen");
            assertThrows(IllegalArgumentException.class, () -> mapper.toCommand(event));
        }

        private CoreTransactionReported coreTransaction(String reference, BigDecimal amount, String currency) {
            return CoreTransactionReported.newBuilder()
                    .setEventId("evt-2")
                    .setOperationReference(reference)
                    .setAmount(amount)
                    .setCurrency(currency)
                    .setOccurredAt(OCCURRED)
                    .build();
        }
    }
}
