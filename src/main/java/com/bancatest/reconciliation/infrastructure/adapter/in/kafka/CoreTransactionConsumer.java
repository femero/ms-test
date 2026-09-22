package com.bancatest.reconciliation.infrastructure.adapter.in.kafka;

import com.bancatest.reconciliation.domain.port.in.RegisterCoreTransactionUseCase;
import com.bancatest.reconciliation.infrastructure.adapter.in.kafka.mapper.CoreTransactionAvroMapper;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.MDC;
import pe.com.bancatest.banking.events.CoreTransactionReported;

/**
 * Consumes core transactions and resolves the matching reconciliation.
 *
 * <p>Orphan transactions and transactions for an already resolved reconciliation return
 * normally, with a warning logged inside the use case. That is deliberate: they are
 * discards, not failures, so they are acknowledged instead of being dead-lettered.
 */
@ApplicationScoped
public class CoreTransactionConsumer {

    private static final String MDC_OPERATION_REFERENCE = "operationReference";

    private final RegisterCoreTransactionUseCase registerCoreTransaction;
    private final CoreTransactionAvroMapper mapper;

    public CoreTransactionConsumer(
            RegisterCoreTransactionUseCase registerCoreTransaction, CoreTransactionAvroMapper mapper) {
        this.registerCoreTransaction = registerCoreTransaction;
        this.mapper = mapper;
    }

    @Incoming("core-transactions-in")
    @RunOnVirtualThread
    public void consume(CoreTransactionReported event) {
        MDC.put(MDC_OPERATION_REFERENCE, event.getOperationReference());
        try {
            registerCoreTransaction.register(mapper.toCommand(event));
        } finally {
            MDC.remove(MDC_OPERATION_REFERENCE);
        }
    }
}
