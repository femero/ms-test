package com.bancatest.reconciliation.infrastructure.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.bancatest.reconciliation.domain.model.Reconciliation;
import com.bancatest.reconciliation.domain.model.vo.AccountNumber;
import com.bancatest.reconciliation.domain.model.vo.Amount;
import com.bancatest.reconciliation.domain.model.vo.AmountDifference;
import com.bancatest.reconciliation.domain.model.vo.Currency;
import com.bancatest.reconciliation.domain.model.vo.OperationReference;
import com.bancatest.reconciliation.domain.model.vo.ReconciliationId;
import com.bancatest.reconciliation.infrastructure.InMemoryReconciliationRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single REST endpoint, exercised through the real resource, mapper and use case.
 *
 * <p>No database: the repository port is replaced by an in-memory double, so the test seeds
 * its own state directly and nothing outside the JVM is touched.
 *
 * <p>Two things are worth pinning here beyond the happy path: that amounts are serialised as
 * JSON strings rather than numbers, since a number would be read back as a double by most
 * clients and lose the precision the whole chain preserves; and that a miss produces an
 * RFC 7807 document with the right content type, not a bare 404.
 */
@QuarkusTest
class ReconciliationResourceTest {

    private static final Currency PEN = Currency.of("PEN");
    private static final Instant CREATED = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant RESOLVED = Instant.parse("2026-09-20T10:05:00Z");

    @Inject
    InMemoryReconciliationRepository.Repository repository;

    @BeforeEach
    void reset() {
        repository.clear();
    }

    @Test
    @DisplayName("a pending reconciliation is returned without core amount, difference or resolution instant")
    void pendingReconciliation() {
        repository.seed(pending("OP-1"));

        given().accept("application/json")
                .when()
                .get("/reconciliations/OP-1")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("operationReference", equalTo("OP-1"))
                .body("status", equalTo("PENDING"))
                .body("paymentAmount.amount", equalTo("100.00"))
                .body("paymentAmount.currency", equalTo("PEN"))
                .body("reconciliationId", notNullValue())
                .body("createdAt", notNullValue())
                .body("coreAmount", nullValue())
                .body("difference", nullValue())
                .body("resolvedAt", nullValue());
    }

    @Test
    @DisplayName("a mismatched reconciliation exposes the exact difference, negative sign included")
    void mismatchedReconciliation() {
        Reconciliation reconciliation = pending("OP-2");
        Amount core = Amount.of("100.50", PEN);
        AmountDifference difference = reconciliation.paymentAmount().subtract(core);
        reconciliation.markAsMismatched(core, difference, RESOLVED);
        repository.seed(reconciliation);

        given().accept("application/json")
                .when()
                .get("/reconciliations/OP-2")
                .then()
                .statusCode(200)
                .body("status", equalTo("MISMATCHED"))
                .body("coreAmount.amount", equalTo("100.50"))
                .body("difference.amount", equalTo("-0.50"))
                .body("difference.currency", equalTo("PEN"))
                .body("resolvedAt", notNullValue());
    }

    @Test
    @DisplayName("amounts travel as JSON strings, never as numbers")
    void amountsAreStrings() {
        repository.seed(pending("OP-3"));

        String body = given().accept("application/json")
                .when()
                .get("/reconciliations/OP-3")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        // RestAssured already applies quarkus.http.root-path, so the path here omits /v1.
        // Quoted: a bare 100.00 would be a JSON number and lose precision on the way out.
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("\"100.00\""), "amount must be a quoted string, got: " + body);
    }

    @Test
    @DisplayName("an unknown reference yields 404 as an RFC 7807 problem document")
    void unknownReferenceYieldsProblemDetail() {
        given().accept("application/json")
                .when()
                .get("/reconciliations/OP-404")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("title", equalTo("Reconciliation not found"))
                .body("type", notNullValue())
                .body("instance", equalTo("/v1/reconciliations/OP-404"));
    }

    private static Reconciliation pending(String reference) {
        return Reconciliation.start(
                ReconciliationId.of(UUID.randomUUID()),
                OperationReference.of(reference),
                AccountNumber.of("1234567890"),
                Amount.of("100.00", PEN),
                CREATED);
    }
}
