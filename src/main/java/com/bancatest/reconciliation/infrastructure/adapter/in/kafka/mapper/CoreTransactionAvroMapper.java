package com.bancatest.reconciliation.infrastructure.adapter.in.kafka.mapper;

import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.port.in.RegisterCoreTransactionCommand;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper.MapperConfig;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import pe.com.bancatest.banking.events.CoreTransactionReported;

/**
 * Translates the inbound core transaction contract into a domain command.
 *
 * <p>The core transaction is never persisted and is not an entity: it arrives, it is
 * compared, it is discarded. This mapper is the whole of its lifetime inside the service.
 */
@Mapper(config = MapperConfig.class)
public interface CoreTransactionAvroMapper {

    @Mapping(target = "amount", expression = "java(toAmount(event.getAmount(), event.getCurrency()))")
    RegisterCoreTransactionCommand toCommand(CoreTransactionReported event);

    default OperationReference toOperationReference(String value) {
        return OperationReference.of(value);
    }

    default Amount toAmount(BigDecimal amount, String currency) {
        return new Amount(amount, Currency.of(currency));
    }
}
