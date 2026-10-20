/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop: the pick decision's two factory contracts.
 *
 * PickDecision is the answer a route produces -- either a passthrough (no cell
 * owns the gesture, so the desktop keeps it) or a consuming route to a named
 * cell. routeTo() refuses a null cellId because a consuming decision that names
 * no cell is a contradiction the dispatcher must never have to defend against.
 * PickRouterTest drives these through the router; this suite pins the record's
 * own factories directly, including the null-owner guard the router never
 * reaches (it filters null ids before it ever calls routeTo).
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Headless tests for {@link PickDecision}'s factory methods. */
class PickDecisionTest {

    @Test
    void passthroughCarriesTheIntentButNoOwnerAndDoesNotConsume() {
        PickDecision decision = PickDecision.passthrough(PickIntent.SELECT);

        assertThat(decision.cellId()).isNull();
        assertThat(decision.intent()).isEqualTo(PickIntent.SELECT);
        assertThat(decision.consumed()).isFalse();
    }

    @Test
    void routeToNamesTheOwnerAndConsumes() {
        PickDecision decision = PickDecision.routeTo("cell-1", PickIntent.DRAG);

        assertThat(decision.cellId()).isEqualTo("cell-1");
        assertThat(decision.intent()).isEqualTo(PickIntent.DRAG);
        assertThat(decision.consumed()).isTrue();
    }

    @Test
    void routeToRejectsANullCellId() {
        // A consuming decision with no owner would make the desktop swallow the
        // gesture and hand it to nobody. Fail at the factory instead of letting a
        // null owner reach the dispatch -- this is the branch the router's own
        // null-id filter means routeTo() never sees in production.
        assertThatThrownBy(() -> PickDecision.routeTo(null, PickIntent.SELECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cellId must not be null");
    }
}
