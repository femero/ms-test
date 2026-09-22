package com.bancatest.reconciliation.infrastructure.adapter.in.rest.mapper;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.model.MonetaryAmount;
import com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.model.ReconciliationResponse;
import com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.model.ReconciliationStatus;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper.MapperConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Translates the aggregate into the DTOs generated from the OpenAPI contract.
 *
 * <p>Neither domain nor JPA types ever cross the wire: what leaves this adapter is generated
 * from the contract, so the contract stays the single source of truth for the API shape.
 *
 * <p>Amounts are rendered as strings, never as JSON numbers. A JSON number deserialises as a
 * double in most clients, which would throw away the decimal precision that
 * {@code BigDecimal} preserves all the way from the database.
 *
 * <p>Under the strict unmapped-target policy, adding a field to the OpenAPI contract fails
 * the build here until it is mapped.
 */
@Mapper(config = MapperConfig.class)
public interface ReconciliationResponseMapper {

    @Mapping(target = "reconciliationId", expression = "java(reconciliation.id().value())")
    @Mapping(target = "operationReference", expression = "java(reconciliation.operationReference().value())")
    @Mapping(target = "status", expression = "java(toApiStatus(reconciliation))")
    @Mapping(target = "paymentAmount", expression = "java(toMonetaryAmount(reconciliation.paymentAmount()))")
    @Mapping(target = "createdAt", expression = "java(toOffsetDateTime(reconciliation.createdAt()))")
    // Absent while PENDING: the field is simply omitted, never sent as an explicit null.
    @Mapping(target = "coreAmount", expression = "java(toMonetaryAmount(reconciliation.coreAmount().orElse(null)))")
    @Mapping(target = "difference", expression = "java(toMonetaryAmount(reconciliation.difference().orElse(null)))")
    @Mapping(target = "resolvedAt", expression = "java(toOffsetDateTime(reconciliation.resolvedAt().orElse(null)))")
    ReconciliationResponse toResponse(Reconciliation reconciliation);

    default ReconciliationStatus toApiStatus(Reconciliation reconciliation) {
        return ReconciliationStatus.fromString(reconciliation.status().name());
    }

    default MonetaryAmount toMonetaryAmount(Amount amount) {
        if (amount == null) {
            return null;
        }
        MonetaryAmount monetary = new MonetaryAmount();
        monetary.setAmount(amount.value().toPlainString());
        monetary.setCurrency(amount.currency().code());
        return monetary;
    }

    default MonetaryAmount toMonetaryAmount(AmountDifference difference) {
        if (difference == null) {
            return null;
        }
        MonetaryAmount monetary = new MonetaryAmount();
        monetary.setAmount(difference.value().toPlainString());
        monetary.setCurrency(difference.currency().code());
        return monetary;
    }

    default OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
