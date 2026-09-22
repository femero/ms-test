package com.bancatest.reconciliation.application.service;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.port.in.QueryReconciliationUseCase;
import com.bancatest.reconciliation.domain.port.out.ReconciliationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * Read-only use case behind the single REST endpoint.
 *
 * <p>Thin on purpose: there is no business decision to make here. Receives the outbound port
 * as a constructor argument, never a concrete implementation.
 */
@ApplicationScoped
public class QueryReconciliationService implements QueryReconciliationUseCase {

    private final ReconciliationRepository repository;

    public QueryReconciliationService(ReconciliationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Optional<Reconciliation> findByOperationReference(OperationReference operationReference) {
        return repository.findByOperationReference(operationReference);
    }
}
