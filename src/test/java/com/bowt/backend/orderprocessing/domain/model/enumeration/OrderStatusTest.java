package com.bowt.backend.orderprocessing.domain.model.enumeration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive matrix test over every (from, to) pair of OrderStatus.
 * <p>
 * This is the 100%-branch critical-path file called for in
 * 06-testing.md § Coverage targets and NFR-4. Every branch of the switch
 * in OrderStatus.canTransition is exercised by construction: 7 states x 7
 * states = 49 assertions, derived from the lifecycle diagram in
 * 01-functional.md, not copied from the implementation under test.
 */
class OrderStatusTest {

    // Expected valid forward transitions — hand-derived from the lifecycle
    // diagram, independent of OrderStatus.canTransition's implementation.
    private static final Set<OrderStatus> FROM_PENDING_VALIDATION =
            EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.INSUFFICIENT_INVENTORY);
    private static final Set<OrderStatus> FROM_PENDING_PAYMENT =
            EnumSet.of(OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.PAYMENT_FAILED);
    private static final Set<OrderStatus> FROM_PAYMENT_AUTHORIZED =
            EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
    private static final Set<OrderStatus> FROM_CONFIRMED =
            EnumSet.of(OrderStatus.CANCELLED);

    private static Set<OrderStatus> expectedValidTargets(OrderStatus from) {
        return switch (from) {
            case PENDING_VALIDATION -> FROM_PENDING_VALIDATION;
            case PENDING_PAYMENT -> FROM_PENDING_PAYMENT;
            case PAYMENT_AUTHORIZED -> FROM_PAYMENT_AUTHORIZED;
            case CONFIRMED -> FROM_CONFIRMED;
            case INSUFFICIENT_INVENTORY, PAYMENT_FAILED, CANCELLED ->
                    EnumSet.noneOf(OrderStatus.class); // terminal — no outgoing edges
        };
    }

    static Stream<Arguments> allStatusPairs() {
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.build();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allStatusPairs")
    void matchesTheExpectedLifecycleMatrix(OrderStatus from, OrderStatus to) {
        boolean expected = expectedValidTargets(from).contains(to);

        assertThat(OrderStatus.canTransition(from, to))
                .as("%s -> %s", from, to)
                .isEqualTo(expected);
    }

    @Test
    void nullFromStatus_alwaysReturnsFalse() {
        for (OrderStatus to : OrderStatus.values()) {
            assertThat(OrderStatus.canTransition(null, to))
                    .as("null -> %s", to)
                    .isFalse();
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (OrderStatus terminal : EnumSet.of(
                OrderStatus.INSUFFICIENT_INVENTORY,
                OrderStatus.PAYMENT_FAILED,
                OrderStatus.CANCELLED)) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(OrderStatus.canTransition(terminal, to))
                        .as("%s should be terminal, but allowed -> %s", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    void noStatusCanTransitionToItself() {
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(OrderStatus.canTransition(s, s))
                    .as("%s -> %s (self-loop)", s, s)
                    .isFalse();
        }
    }

    @Test
    void confirmedCanOnlyCancelNotRevertEarlier() {
        // Regression guard for OI-5: CONFIRMED -> CANCELLED is intentionally
        // allowed, but CONFIRMED must never go "backwards" in the pipeline.
        assertThat(OrderStatus.canTransition(OrderStatus.CONFIRMED, OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.canTransition(OrderStatus.CONFIRMED, OrderStatus.PAYMENT_AUTHORIZED)).isFalse();
        assertThat(OrderStatus.canTransition(OrderStatus.CONFIRMED, OrderStatus.PENDING_PAYMENT)).isFalse();
    }

    @Test
    void paymentAuthorizedIsCancellableBeforeConfirmation() {
        // Regression guard for OI-5: avoids an unrecoverable stuck state if
        // CONFIRMED is never reached after a successful payment authorization.
        assertThat(OrderStatus.canTransition(OrderStatus.PAYMENT_AUTHORIZED, OrderStatus.CANCELLED)).isTrue();
    }
}