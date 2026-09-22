package com.bancatest.reconciliation.infrastructure.adapter.out.messaging.mapper;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper.MapperConfig;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import pe.com.bancatest.reconciliation.events.DiscrepancyDetected;
import pe.com.bancatest.reconciliation.events.ReconciliationMatched;

/**
 * Translates a resolved aggregate into the outbound Avro contracts.
 *
 * <p>Every target field is an expression, because each one unwraps a value object or an
 * {@link java.util.Optional}. What MapStruct contributes here is not the mapping code but the
 * strict unmapped-target policy: add a field to either {@code .avsc} and the build fails
 * naming it, instead of publishing a record with a silent null in it.
 *
 * <p>The schemas are generated with {@code createSetters=false}, so MapStruct drives Avro's
 * builder rather than setters.
 *
 * <p>Called only for resolved reconciliations, so {@code coreAmount} and {@code difference}
 * are always present. {@code orElseThrow} is the right call: their absence would mean the
 * aggregate's third invariant had been broken, which is a bug rather than a data case.
 */
@Mapper(config = MapperConfig.class)
public interface ReconciliationEventMapper {

    @Mapping(target = "eventId", expression = "java(newEventId())")
    @Mapping(target = "reconciliationId", expression = "java(reconciliation.id().value().toString())")
    @Mapping(target = "operationReference", expression = "java(reconciliation.operationReference().value())")
    @Mapping(target = "amount", expression = "java(reconciliation.paymentAmount().value())")
    @Mapping(target = "currency", expression = "java(reconciliation.paymentAmount().currency().code())")
    @Mapping(target = "difference", expression = "java(reconciliation.difference().orElseThrow().value())")
    @Mapping(target = "reconciledAt", expression = "java(reconciliation.resolvedAt().orElseThrow())")
    ReconciliationMatched toMatched(Reconciliation reconciliation);

    @Mapping(target = "eventId", expression = "java(newEventId())")
    @Mapping(target = "reconciliationId", expression = "java(reconciliation.id().value().toString())")
    @Mapping(target = "operationReference", expression = "java(reconciliation.operationReference().value())")
    @Mapping(target = "paymentAmount", expression = "java(reconciliation.paymentAmount().value())")
    @Mapping(target = "coreAmount", expression = "java(reconciliation.coreAmount().orElseThrow().value())")
    @Mapping(target = "difference", expression = "java(reconciliation.difference().orElseThrow().value())")
    @Mapping(target = "currency", expression = "java(reconciliation.paymentAmount().currency().code())")
    @Mapping(target = "detectedAt", expression = "java(reconciliation.resolvedAt().orElseThrow())")
    DiscrepancyDetected toDiscrepancy(Reconciliation reconciliation);

    /**
     * Identifies this publication, not the operation. Downstream consumers may use it for
     * their own deduplication; ours keys on the operation reference.
     */
    default String newEventId() {
        return UUID.randomUUID().toString();
    }
}
