package com.bancatest.reconciliation.infrastructure.adapter.out.persistence;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.port.out.ReconciliationRepository;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.entity.ReconciliationEntity;
import com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper.ReconciliationEntityMapper;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter implementing the repository port.
 *
 * <p>Uses {@code PanacheRepositoryBase} over a separate JPA entity. Panache Active Record is
 * forbidden: making the aggregate extend a framework class would drag Hibernate straight
 * into the domain, which is precisely what the hexagonal boundary exists to prevent.
 *
 * <p>{@code PanacheRepositoryBase} rather than {@code PanacheRepository} because the primary
 * key is a {@link UUID}, not a {@code Long}.
 */
@ApplicationScoped
public class PanacheReconciliationRepository
        implements ReconciliationRepository, PanacheRepositoryBase<ReconciliationEntity, UUID> {

    private final ReconciliationEntityMapper mapper;

    public PanacheReconciliationRepository(ReconciliationEntityMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Inserts a new reconciliation or updates an existing one.
     *
     * <p>An insert whose operation reference already exists will be rejected by the unique
     * constraint. That rejection is the real idempotency guarantee and the caller is
     * expected to treat it as a duplicate discarded successfully, not as a failure.
     */
    @Override
    public void save(Reconciliation reconciliation) {
        ReconciliationEntity existing = findById(reconciliation.id().value());
        if (existing == null) {
            persist(mapper.toEntity(reconciliation));
        } else {
            // Managed entity: mutating it is enough, Hibernate flushes the change.
            mapper.applyToEntity(reconciliation, existing);
        }
    }

    @Override
    public Optional<Reconciliation> findByOperationReference(OperationReference operationReference) {
        return find("operationReference", operationReference.value())
                .firstResultOptional()
                .map(mapper::toDomain);
    }
}
