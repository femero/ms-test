package com.bancatest.reconciliation.infrastructure.adapter.in.kafka;

import com.bancatest.reconciliation.domain.port.in.ReconcilePaymentUseCase;
import com.bancatest.reconciliation.infrastructure.adapter.in.kafka.mapper.PaymentAvroMapper;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.MDC;
import pe.com.bancatest.payments.events.PaymentExecuted;

/**
 * Consumes reported payments.
 *
 * <p>Blocking, on a virtual thread. Returning normally acknowledges the record, so the
 * offset is committed after processing, never before. Throwing leaves it to the configured
 * failure strategy, which writes the record to the dead-letter queue and commits the offset
 * so consumption continues.
 *
 * <p>Note what is <em>not</em> caught here: duplicates are handled inside the use case and
 * return normally, so they are acknowledged rather than dead-lettered. Only genuinely
 * invalid input reaches the DLQ.
 */
@ApplicationScoped
public class PaymentConsumer {

    private static final String MDC_OPERATION_REFERENCE = "operationReference";

    private final ReconcilePaymentUseCase reconcilePayment;
    private final PaymentAvroMapper mapper;

    public PaymentConsumer(ReconcilePaymentUseCase reconcilePayment, PaymentAvroMapper mapper) {
        this.reconcilePayment = reconcilePayment;
        this.mapper = mapper;
    }

    @Incoming("payments-in")
    @RunOnVirtualThread
    public void consume(PaymentExecuted event) {
        MDC.put(MDC_OPERATION_REFERENCE, event.getOperationReference());
        try {
            reconcilePayment.reconcile(mapper.toCommand(event));
        } finally {
            MDC.remove(MDC_OPERATION_REFERENCE);
        }
    }
}
