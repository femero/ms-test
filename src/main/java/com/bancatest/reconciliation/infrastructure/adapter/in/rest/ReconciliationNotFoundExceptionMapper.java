package com.bancatest.reconciliation.infrastructure.adapter.in.rest;

import com.bancatest.reconciliation.infrastructure.adapter.in.rest.generated.model.ProblemDetail;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;

/**
 * Renders a missing reconciliation as an RFC 7807 problem document.
 *
 * <p>This is what keeps the generated interface returning a typed
 * {@code ReconciliationResponse} instead of a bare {@code Response}: the 404 path leaves
 * through here rather than through the method signature.
 */
@Provider
public class ReconciliationNotFoundExceptionMapper implements ExceptionMapper<ReconciliationNotFoundException> {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final URI PROBLEM_TYPE = URI.create("https://bancatest.com/problems/reconciliation-not-found");

    @Override
    public Response toResponse(ReconciliationNotFoundException exception) {
        ProblemDetail problem = new ProblemDetail();
        problem.setType(PROBLEM_TYPE);
        problem.setTitle("Reconciliation not found");
        problem.setStatus(Response.Status.NOT_FOUND.getStatusCode());
        problem.setDetail(exception.getMessage());
        problem.setInstance(URI.create("/v1/reconciliations/" + exception.operationReference()));

        return Response.status(Response.Status.NOT_FOUND)
                .type(PROBLEM_JSON)
                .entity(problem)
                .build();
    }
}
