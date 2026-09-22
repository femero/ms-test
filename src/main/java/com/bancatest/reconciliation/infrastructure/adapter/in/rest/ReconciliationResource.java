package com.bancatest.reconciliation.infrastructure.adapter.in.rest;

import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.port.in.QueryReconciliationUseCase;
import com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.api.ReconciliationsApi;
import com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.model.ReconciliationResponse;
import com.bancatest.reconciliation.infrastructure.adapter.in.rest.mapper.ReconciliationResponseMapper;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.MDC;

/**
 * The single REST endpoint, implementing the interface generated from the contract.
 *
 * <p>Implementing rather than declaring is what makes contract-first real: adding an
 * operation to the API means editing the YAML, not this class. There are no
 * {@code @Schema}, {@code @Operation} or {@code @APIResponse} annotations here by design;
 * the contract owns that information.
 *
 * <p>Runs on a virtual thread, consistent with the blocking model the constitution mandates.
 */
@ApplicationScoped
public class ReconciliationResource implements ReconciliationsApi {

    private static final String MDC_OPERATION_REFERENCE = "operationReference";

    private final QueryReconciliationUseCase queryReconciliation;
    private final ReconciliationResponseMapper mapper;

    public ReconciliationResource(QueryReconciliationUseCase queryReconciliation, ReconciliationResponseMapper mapper) {
        this.queryReconciliation = queryReconciliation;
        this.mapper = mapper;
    }

    @Override
    @RunOnVirtualThread
    public ReconciliationResponse getReconciliationByOperationReference(String operationReference) {
        MDC.put(MDC_OPERATION_REFERENCE, operationReference);
        try {
            return queryReconciliation
                    .findByOperationReference(OperationReference.of(operationReference))
                    .map(mapper::toResponse)
                    .orElseThrow(() -> new ReconciliationNotFoundException(operationReference));
        } finally {
            MDC.remove(MDC_OPERATION_REFERENCE);
        }
    }
}
