package com.bancatest.reconciliation.infrastructure.adapter.out.messaging;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.port.out.ReconciliationEventPublisher;
import com.bancatest.reconciliation.infrastructure.adapter.out.messaging.mapper.ReconciliationEventMapper;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import pe.com.bancatest.reconciliation.events.DiscrepancyDetected;
import pe.com.bancatest.reconciliation.events.ReconciliationMatched;

/**
 * Publishes resolved reconciliations to their respective channels.
 *
 * <p>Uses emitters rather than {@code @Outgoing} methods because publication is driven by a
 * use case completing, not by a stream being pulled.
 *
 * <p>Every record is keyed by the operation reference, so all events for one operation land
 * on the same partition and keep their relative order.
 *
 * <p>Called after the transaction has committed. There is no outbox: if this fails, the
 * reconciliation stays correctly resolved in the database but its event is lost, with no
 * recovery path. That is an accepted risk, documented in the README, and must not be turned
 * into a retry here.
 */
@ApplicationScoped
public class KafkaReconciliationEventPublisher implements ReconciliationEventPublisher {

    private final Emitter<Record<String, ReconciliationMatched>> matchedEmitter;
    private final Emitter<Record<String, DiscrepancyDetected>> discrepancyEmitter;
    private final ReconciliationEventMapper mapper;

    public KafkaReconciliationEventPublisher(
            @Channel("results-out") Emitter<Record<String, ReconciliationMatched>> matchedEmitter,
            @Channel("discrepancies-out") Emitter<Record<String, DiscrepancyDetected>> discrepancyEmitter,
            ReconciliationEventMapper mapper) {
        this.matchedEmitter = matchedEmitter;
        this.discrepancyEmitter = discrepancyEmitter;
        this.mapper = mapper;
    }

    @Override
    public void publishMatched(Reconciliation reconciliation) {
        matchedEmitter.send(Record.of(reconciliation.operationReference().value(), mapper.toMatched(reconciliation)));
    }

    @Override
    public void publishMismatched(Reconciliation reconciliation) {
        discrepancyEmitter.send(
                Record.of(reconciliation.operationReference().value(), mapper.toDiscrepancy(reconciliation)));
    }
}
