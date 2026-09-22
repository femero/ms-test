package com.bancatest.reconciliation.infrastructure.adapter.in.kafka.mapper;

import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.port.in.ReconcilePaymentCommand;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper.MapperConfig;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import pe.com.bancatest.payments.events.PaymentExecuted;

/**
 * Translates the inbound Avro contract into a domain command.
 *
 * <p>The Avro record never reaches the aggregate. It is an integration contract owned by
 * another system, and letting it through would tie the domain to someone else's schema
 * evolution.
 *
 * <p>{@code eventId} is deliberately not carried inwards: idempotency keys on the operation
 * reference alone. The command has no such component, so there is nothing to map it to.
 *
 * <p>Value-object construction is where malformed input is rejected. A blank reference or a
 * negative amount raises inside these conversions, the consumer lets it propagate, and the
 * message reaches the dead-letter queue (FR-022).
 */
@Mapper(config = MapperConfig.class)
public interface PaymentAvroMapper {

    // amount and currency are two Avro fields collapsing into one value object, which no
    // field-to-field rule can express: hence the explicit expression.
    @Mapping(target = "amount", expression = "java(toAmount(event.getAmount(), event.getCurrency()))")
    ReconcilePaymentCommand toCommand(PaymentExecuted event);

    default OperationReference toOperationReference(String value) {
        return OperationReference.of(value);
    }

    default AccountNumber toAccountNumber(String value) {
        return AccountNumber.of(value);
    }

    default Amount toAmount(BigDecimal amount, String currency) {
        return new Amount(amount, Currency.of(currency));
    }
}
