package com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.ReconciliationSnapshot;
import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.entity.ReconciliationEntity;
import java.math.BigDecimal;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Translates between the aggregate and its JPA row.
 *
 * <p>Rehydration goes through {@link ReconciliationSnapshot}, which turns out to be exactly
 * what MapStruct needs: the snapshot is a plain record, so it can be built field by field
 * under the strict unmapped-target policy, and a single {@code default} method then hands it
 * to {@code reconstitute}. The aggregate is never rebuilt with {@code new} or setters.
 *
 * <p>The reverse direction is written as expressions because every target field either
 * unwraps a value object or splits one into figure and currency, and neither is expressible
 * as a field-to-field rule. The strict policy still earns its place: add a column to the
 * entity and the build fails until it is mapped, instead of writing a silent null.
 */
@Mapper(config = MapperConfig.class)
public interface ReconciliationEntityMapper {

    @Mapping(
            target = "paymentAmount",
            expression = "java(toAmount(entity.getPaymentAmount(), entity.getPaymentCurrency()))")
    @Mapping(target = "coreAmount", expression = "java(toAmount(entity.getCoreAmount(), entity.getCoreCurrency()))")
    @Mapping(
            target = "difference",
            expression = "java(toDifference(entity.getDifferenceAmount(), entity.getDifferenceCurrency()))")
    ReconciliationSnapshot toSnapshot(ReconciliationEntity entity);

    /** Rehydrates the aggregate from a stored row. */
    default Reconciliation toDomain(ReconciliationEntity entity) {
        return Reconciliation.reconstitute(toSnapshot(entity));
    }

    /**
     * Copies aggregate state onto a row.
     *
     * <p>Takes the target entity instead of creating one, so the same method serves both an
     * insert and an update of a managed entity.
     */
    @Mapping(target = "id", expression = "java(reconciliation.id().value())")
    @Mapping(target = "operationReference", expression = "java(reconciliation.operationReference().value())")
    @Mapping(target = "accountNumber", expression = "java(reconciliation.accountNumber().value())")
    @Mapping(target = "paymentAmount", expression = "java(reconciliation.paymentAmount().value())")
    @Mapping(target = "paymentCurrency", expression = "java(reconciliation.paymentAmount().currency().code())")
    @Mapping(target = "coreAmount", expression = "java(valueOf(reconciliation.coreAmount().orElse(null)))")
    @Mapping(target = "coreCurrency", expression = "java(currencyOf(reconciliation.coreAmount().orElse(null)))")
    @Mapping(target = "differenceAmount", expression = "java(valueOf(reconciliation.difference().orElse(null)))")
    @Mapping(target = "differenceCurrency", expression = "java(currencyOf(reconciliation.difference().orElse(null)))")
    @Mapping(target = "status", expression = "java(reconciliation.status())")
    @Mapping(target = "createdAt", expression = "java(reconciliation.createdAt())")
    @Mapping(target = "resolvedAt", expression = "java(reconciliation.resolvedAt().orElse(null))")
    void applyToEntity(Reconciliation reconciliation, @MappingTarget ReconciliationEntity entity);

    default ReconciliationEntity toEntity(Reconciliation reconciliation) {
        ReconciliationEntity entity = ReconciliationEntity.empty();
        applyToEntity(reconciliation, entity);
        return entity;
    }

    default ReconciliationId toReconciliationId(UUID value) {
        return ReconciliationId.of(value);
    }

    default OperationReference toOperationReference(String value) {
        return OperationReference.of(value);
    }

    default AccountNumber toAccountNumber(String value) {
        return AccountNumber.of(value);
    }

    /** Null figure or null currency means the column pair is empty, which is legal while PENDING. */
    default Amount toAmount(BigDecimal value, String currency) {
        return value == null || currency == null ? null : new Amount(value, Currency.of(currency));
    }

    default AmountDifference toDifference(BigDecimal value, String currency) {
        return value == null || currency == null ? null : new AmountDifference(value, Currency.of(currency));
    }

    default BigDecimal valueOf(Amount amount) {
        return amount == null ? null : amount.value();
    }

    default BigDecimal valueOf(AmountDifference difference) {
        return difference == null ? null : difference.value();
    }

    default String currencyOf(Amount amount) {
        return amount == null ? null : amount.currency().code();
    }

    default String currencyOf(AmountDifference difference) {
        return difference == null ? null : difference.currency().code();
    }
}
