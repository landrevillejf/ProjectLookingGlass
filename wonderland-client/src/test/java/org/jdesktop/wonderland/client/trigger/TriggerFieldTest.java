/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.client.trigger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1.2: the enter/edge contract, proven over a position walk with no clock and
 * no render context. The phase's whole value is that a zone fires once per
 * crossing and stays quiet while the walker loiters -- asserted here directly.
 */
class TriggerFieldTest {

    @Test
    void crossingIntoAZoneFiresExactlyOneEnter() {
        TriggerField field = new TriggerField(0f, 0f, 0f);
        field.addZone(TriggerZone.open("clinic", 10f, 0f, 2f, 2f));

        assertThat(field.moveTo(5f, 0f, 0f).entered()).isEmpty();       // still outside
        TriggerField.Transitions in = field.moveTo(10f, 0f, 0f);      // step inside
        assertThat(in.entered()).containsExactly("clinic");
        assertThat(in.exited()).isEmpty();
        assertThat(field.moveTo(11f, 0f, 0f).isEmpty()).isTrue();     // loitering is silent
    }

    @Test
    void leavingAZoneFiresTheExit() {
        TriggerField field = new TriggerField(10f, 0f, 0f);
        field.addZone(TriggerZone.open("clinic", 10f, 0f, 2f, 2f));
        assertThat(field.currentlyInside()).containsExactly("clinic"); // primed on add

        TriggerField.Transitions out = field.moveTo(30f, 0f, 0f);
        assertThat(out.exited()).containsExactly("clinic");
        assertThat(out.entered()).isEmpty();
        assertThat(field.currentlyInside()).isEmpty();
    }

    @Test
    void overlappingZonesReportBothEdgesInOneMove() {
        TriggerField field = new TriggerField(0f, 0f, 0f);
        field.addZone(TriggerZone.open("lobby", 5f, 0f, 3f, 3f));
        field.addZone(TriggerZone.open("podium", 6f, 0f, 1f, 1f));

        TriggerField.Transitions t = field.moveTo(6f, 0f, 0f);
        assertThat(t.entered()).containsExactly("lobby", "podium");      // registration order
    }

    @Test
    void aVerticalBandExcludesAWalkerAboveIt() {
        TriggerField field = new TriggerField(0f, 0f, 0f);
        field.addZone(TriggerZone.of("podium", 10f, 0f, 0f, 2f, 1f, 2f)); // y band -1..1
        // Standing at head height above the band is not "on" the podium.
        assertThat(field.moveTo(10f, 5f, 0f).entered()).isEmpty();
        // Dropping into the band enters it.
        assertThat(field.moveTo(10f, 0f, 0f).entered()).containsExactly("podium");
    }

    @Test
    void reassessWithoutMovingIsSilent() {
        TriggerField field = new TriggerField(0f, 0f, 0f);
        field.addZone(TriggerZone.open("a", 0f, 0f, 5f, 5f));
        field.moveTo(1f, 0f, 0f); // already inside from add prime? no: add at (0,0) -> primed
        assertThat(field.reassess().isEmpty()).isTrue();
    }

    @Test
    void aNullZoneIsRejected() {
        TriggerField field = new TriggerField(0f, 0f, 0f);
        assertThatThrownBy(() -> field.addZone(null))
                .isInstanceOf(IllegalArgumentException.class);
        // The refusal did not half-register: the field still holds nothing.
        assertThat(field.zoneCount()).isZero();
        field.addZone(TriggerZone.open("room", 0f, 0f, 1f, 1f));
        assertThat(field.zoneCount()).isEqualTo(1);
    }

    @Test
    void aWalkerUnderneathABandedZoneIsNotInsideIt() {
        // The other band edge: above the ceiling was proven; a podium whose
        // band is y -1..1 must not fire for a walker standing under it (a
        // mezzanine visitor) either -- contains() is a volume, not a column.
        TriggerField field = new TriggerField(0f, 0f, 0f);
        field.addZone(TriggerZone.of("podium", 10f, 0f, 0f, 2f, 1f, 2f));

        assertThat(field.moveTo(10f, -5f, 0f).entered()).isEmpty();
        assertThat(field.moveTo(10f, -0.5f, 0f).entered())
                .as("wading up into the band from below enters on the crossing")
                .containsExactly("podium");
    }

    @Test
    void theBoundaryOfAZoneIsInsideIt() {
        // contains() is closed: standing exactly on the authored min-x face
        // (or any other face) is inside, so an enter fires the moment the
        // walker touches the threshold rather than one step later.
        TriggerZone doorway = TriggerZone.open("door", 0f, 0f, 1f, 1f); // x -1..1, z -1..1

        assertThat(doorway.contains(-1f, 0f, 1f)).isTrue();
        assertThat(doorway.contains(-1.01f, 0f, 1f)).isFalse();
        // Every other rejection face, in the operator's own short-circuit
        // order: east, north, south -- an open zone is a column, so no y
        // answer is made here -- and then both y faces of a closed box.
        assertThat(doorway.contains(1.01f, 0f, 0f)).isFalse();
        assertThat(doorway.contains(0f, 0f, -1.01f)).isFalse();
        assertThat(doorway.contains(0f, 0f, 1.01f)).isFalse();
        TriggerZone room = TriggerZone.of("room", 0f, 0f, 0f, 1f, 1f, 1f); // y -1..1
        assertThat(room.contains(0f, -1.01f, 0f)).isFalse();
        assertThat(room.contains(0f, 1.01f, 0f)).isFalse();
        assertThat(room.contains(0f, 0f, 0f)).isTrue();
    }

    @Test
    void transitionsKnowAPureExitIsNotEmpty() {
        // isEmpty()'s second operand: a move that only leaves a zone still
        // happened, and a host that asks "anything to report?" must be told
        // yes on the exit side too, not just the enter side.
        TriggerField field = new TriggerField(0f, 0f, 0f);
        field.addZone(TriggerZone.open("room", 0f, 0f, 1f, 1f));

        TriggerField.Transitions exitOnly = field.moveTo(10f, 0f, 0f);

        assertThat(exitOnly.entered()).isEmpty();
        assertThat(exitOnly.exited()).containsExactly("room");
        assertThat(exitOnly.isEmpty()).isFalse();

        var reEnter = field.moveTo(0f, 0f, 0f);
        assertThat(reEnter.isEmpty())
                .as("the short-circuit side: an enter is not nothing either")
                .isFalse();
    }
}
