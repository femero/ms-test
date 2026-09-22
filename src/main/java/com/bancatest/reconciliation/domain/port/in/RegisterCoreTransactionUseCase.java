package com.bancatest.reconciliation.domain.port.in;

/**
 * Inbound port for a core transaction.
 *
 * <p>Resolves the PENDING reconciliation carrying the same operation reference. Discards the
 * command, with a warning, when no reconciliation exists (FR-021) or when it is already
 * resolved (FR-020). Neither case is an error.
 */
public interface RegisterCoreTransactionUseCase {

    void register(RegisterCoreTransactionCommand command);
}
