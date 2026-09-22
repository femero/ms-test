package com.bancatest.reconciliation.domain.model.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Walks the complete transition matrix: every ordered pair of states, not only the legal
 * ones. Testing just the happy transitions would pass even if the machine allowed
 * everything.
 *
 * <p>Plain JUnit. No {@code @QuarkusTest}, no CDI: this is pure logic.
 */
class ReconciliationStatusTest {

    /** The only two transitions the specification declares. */
    private static final Set<String> DECLARED = Set.of("PENDING->MATCHED", "PENDING->MISMATCHED");

    static Stream<Arguments> fullMatrix() {
        return Stream.of(ReconciliationStatus.values())
                .flatMap(from -> Stream.of(ReconciliationStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("fullMatrix")
    @DisplayName("only declared transitions are permitted, across all 9 combinations")
    void onlyDeclaredTransitionsArePermitted(ReconciliationStatus from, ReconciliationStatus to) {
        boolean expected = DECLARED.contains(from.name() + "->" + to.name());
        assertEquals(
                expected,
                from.canTransitionTo(to),
                "Transition " + from + " -> " + to + (expected ? " should be allowed" : " must be rejected"));
    }

    @Test
    @DisplayName("the matrix covers exactly 9 combinations, of which 2 are legal")
    void matrixShapeIsAsSpecified() {
        long total = fullMatrix().count();
        long allowed = fullMatrix()
                .filter(args ->
                        ((ReconciliationStatus) args.get()[0]).canTransitionTo((ReconciliationStatus) args.get()[1]))
                .count();
        assertEquals(9, total, "3 states means 9 ordered pairs");
        assertEquals(2, allowed, "only PENDING->MATCHED and PENDING->MISMATCHED are declared");
    }

    @Test
    @DisplayName("MATCHED and MISMATCHED are terminal, PENDING is not")
    void terminalStates() {
        assertFalse(ReconciliationStatus.PENDING.isTerminal());
        assertTrue(ReconciliationStatus.MATCHED.isTerminal());
        assertTrue(ReconciliationStatus.MISMATCHED.isTerminal());
    }

    @Test
    @DisplayName("a null target is never a valid transition")
    void nullTargetIsRejected() {
        for (ReconciliationStatus from : ReconciliationStatus.values()) {
            assertFalse(from.canTransitionTo(null));
        }
    }

    @Test
    @DisplayName("state is persisted by name, never by ordinal")
    void namesAreStable() {
        assertEquals("PENDING", ReconciliationStatus.PENDING.name());
        assertEquals("MATCHED", ReconciliationStatus.MATCHED.name());
        assertEquals("MISMATCHED", ReconciliationStatus.MISMATCHED.name());
    }
}
