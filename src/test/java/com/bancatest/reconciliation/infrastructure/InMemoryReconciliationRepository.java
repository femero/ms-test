package com.bancatest.reconciliation.infrastructure;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.port.out.ProcessedOperationStore;
import com.bancatest.reconciliation.domain.port.out.ReconciliationRepository;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CDI replacements for the two outbound ports that would otherwise reach a database and a
 * Redis instance.
 *
 * <p>{@code @Mock} makes these alternatives take precedence over the real adapters for the
 * whole test run, so the consumers, the REST resource and the use cases are exercised
 * through their real wiring while nothing leaves the JVM.
 *
 * <p>The repository double keeps the one behaviour that matters for correctness: an
 * operation reference maps to at most one reconciliation.
 */
public final class InMemoryReconciliationRepository {

    private InMemoryReconciliationRepository() {}

    @Mock
    @ApplicationScoped
    public static class Repository implements ReconciliationRepository {

        private final Map<String, Reconciliation> stored = new ConcurrentHashMap<>();

        @Inject
        TransactionManager transactionManager;

        /**
         * Refuses to store anything outside an active transaction.
         *
         * <p>The real repository mutates a managed entity and relies on the flush at commit.
         * With no transaction there is no flush, so the write is lost silently - no
         * exception, just a reconciliation that looks resolved in memory, publishes its
         * event, and stays PENDING in the database. This guard turns that silence into a
         * test failure.
         */
        @Override
        public void save(Reconciliation reconciliation) {
            requireActiveTransaction(reconciliation);
            stored.put(reconciliation.operationReference().value(), reconciliation);
        }

        private void requireActiveTransaction(Reconciliation reconciliation) {
            try {
                if (transactionManager.getStatus() != Status.STATUS_ACTIVE) {
                    throw new IllegalStateException(
                            "save(" + reconciliation.operationReference().value()
                                    + ") ran without an active transaction: the write would be lost silently");
                }
            } catch (SystemException e) {
                throw new IllegalStateException("Could not determine transaction status", e);
            }
        }

        @Override
        public Optional<Reconciliation> findByOperationReference(OperationReference operationReference) {
            return Optional.ofNullable(stored.get(operationReference.value()));
        }

        /**
         * Stores a reconciliation without the transaction guard, for tests that need to seed
         * state before exercising a read path. Kept separate from {@link #save} on purpose:
         * the guard must stay strict on the port method, which is the one production uses.
         */
        public void seed(Reconciliation reconciliation) {
            stored.put(reconciliation.operationReference().value(), reconciliation);
        }

        public void clear() {
            stored.clear();
        }

        public int size() {
            return stored.size();
        }
    }

    @Mock
    @ApplicationScoped
    public static class ProcessedOperations implements ProcessedOperationStore {

        private final Set<String> seen = ConcurrentHashMap.newKeySet();

        @Override
        public boolean markAsProcessed(OperationReference operationReference) {
            return seen.add(operationReference.value());
        }

        public void clear() {
            seen.clear();
        }
    }
}
