package com.bancatest.reconciliation.infrastructure.adapter.in.rest;

/**
 * Signals that no reconciliation exists for the requested operation reference.
 *
 * <p>Lives in the REST adapter, not in the domain: for the domain an absent reconciliation is
 * an ordinary {@code Optional.empty()}, and only this adapter has to turn that into an HTTP
 * status. Raising it here keeps the resource method returning a typed body instead of an
 * untyped {@code Response} just to carry a 404.
 */
public class ReconciliationNotFoundException extends RuntimeException {

    private final String operationReference;

    public ReconciliationNotFoundException(String operationReference) {
        super("No reconciliation exists for operation reference " + operationReference);
        this.operationReference = operationReference;
    }

    public String operationReference() {
        return operationReference;
    }
}
