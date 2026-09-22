package com.bancatest.reconciliation.infrastructure.adapter.out.persistence;

import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.domain.port.out.ReconciliationIdProvider;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Supplies UUIDv7 identities, generated in the application rather than by the database.
 *
 * <p>Generating here is what lets {@code Reconciliation.start(...)} return a complete
 * aggregate before anything is persisted. UUIDv7 is time-ordered, so the primary-key index
 * does not fragment the way random UUIDv4 keys do.
 *
 * <p>The generator is thread-safe, which matters under virtual threads.
 */
@ApplicationScoped
public class UuidV7ReconciliationIdProvider implements ReconciliationIdProvider {

    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    @Override
    public ReconciliationId nextId() {
        return ReconciliationId.of(generator.generate());
    }
}
