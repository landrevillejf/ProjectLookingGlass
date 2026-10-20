/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P2.4: the PVS lifecycle, headless.
 *
 * The kernel's own geometry was pinned in OctantCullerTest at P0.2; what is
 * asserted here is the OWNER half -- that the mirror never double-counts a
 * re-attached cell, that a detached cell really leaves the next answer, and
 * that the padding keeps the promise the class makes out loud: conservative
 * for drawing, a far cell may be kept, but a near cell is never dropped.
 */
package org.jdesktop.wonderland.client.cull;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldPVSTest {

    @Test
    void anEmptyWorldAnswersEmptyRatherThanThrowing() {
        WorldPVS pvs = new WorldPVS(10f, 100f);

        assertThat(pvs.size()).isZero();
        PotentialVisibleSet empty = pvs.visibleFrom(0, 0, 0);
        assertThat(empty.visibleCount()).isZero();
        // The empty answer's own rendering is what a shell logs when it wants
        // to know why nothing drew: "0 of 0" says the world was empty, not
        // that the culler threw everything away.
        assertThat(empty.toString()).isEqualTo("PVS{0/0}");
        assertThat(pvs.isVisible("nowhere", 0, 0, 0)).isFalse();
    }

    @Test
    void aNearCellIsVisibleAndAFarOneIsCulled() {
        WorldPVS pvs = new WorldPVS(10f, 50f);
        pvs.register("here", 0, 0, 0, 3);
        pvs.register("there", 500, 0, 0, 3);

        PotentialVisibleSet visible = pvs.visibleFrom(0, 0, 0);

        assertThat(visible.contains("here")).isTrue();
        assertThat(visible.contains("there")).as("beyond the view radius").isFalse();
        assertThat(pvs.isVisible("there", 0, 0, 0)).isFalse();
    }

    @Test
    void reRegisteringAMovedCellReplacesItRatherThanDoublingIt() {
        // The 2006 client re-attaches a moved cell without a detach; the
        // kernel's contract is "same id twice = two volumes", so the owner's
        // job is the mirror: the world holds the cell ONCE, at its new place.
        WorldPVS pvs = new WorldPVS(10f, 50f);
        pvs.register("cell", 0, 0, 0, 2);
        pvs.register("cell", 400, 0, 0, 2);

        assertThat(pvs.size()).isEqualTo(1);
        assertThat(pvs.visibleFrom(0, 0, 0).contains("cell"))
                .as("the moved cell left the near set")
                .isFalse();
        assertThat(pvs.visibleFrom(400, 0, 0).contains("cell"))
                .as("and joined the far one")
                .isTrue();
    }

    @Test
    void aDetachedCellLeavesTheNextAnswer() {
        WorldPVS pvs = new WorldPVS(10f, 50f);
        pvs.register("a", 0, 0, 0, 2);
        pvs.register("b", 5, 0, 0, 2);

        pvs.unregister("b");
        pvs.unregister("b"); // an unknown (and already-gone) name is a no-op

        assertThat(pvs.size()).isEqualTo(1);
        assertThat(pvs.visibleFrom(0, 0, 0).ids()).containsExactly("a");
    }

    @Test
    void thePaddingKeepsAStraddlingCellVisibleNeverDropsANearOne() {
        // The promise in the class comment: a cell whose RAW centre sits just
        // past the view radius but whose padded sphere reaches it stays in --
        // a kept cell costs frames, a dropped one eats the world.
        WorldPVS pvs = new WorldPVS(10f, 50f);
        pvs.register("edge", 52, 0, 0, 1); // centre 52 > 50, padded sphere reaches

        assertThat(pvs.visibleFrom(0, 0, 0).contains("edge")).isTrue();
    }

    @Test
    void aNullNameIsRejectedTheWayTheKernelRejectsIt() {
        WorldPVS pvs = new WorldPVS(10f, 50f);

        assertThatThrownBy(() -> pvs.register(null, 0, 0, 0, 1))
                .isInstanceOf(NullPointerException.class);
    }
}
