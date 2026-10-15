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
    }
}
