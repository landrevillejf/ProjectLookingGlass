/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: the one piece of arithmetic a name card turns on.
 *
 * A card is a flat quad with a +Z normal; the yaw is what keeps its face toward
 * the reader instead of showing them its edge, which is an invisible line. Only
 * yaw, never pitch: a tag that tilted to meet an eye above it would stop being
 * text. Both of those are claims about a number, so both are testable without a
 * universe.
 */
package org.jdesktop.wonderland.renderer.view;

import org.jogamp.vecmath.Point3d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.Test;

class NameTagTest {

    @Test
    void aReaderStraightAheadSeesTheFaceItWasAuthoredWith() {
        assertThat(NameTag.yawToward(new Point3d(0, 1.6, 5), new Point3d(0, 2, 0)))
                .as("eye on +Z of the card, whose normal is +Z: no turn")
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void aCardTurnsToMeetAReaderOffToTheSide() {
        assertThat(NameTag.yawToward(new Point3d(5, 1.6, 0), new Point3d(0, 2, 0)))
                .as("a reader due east turns the card a quarter turn")
                .isCloseTo(Math.PI / 2, within(1e-9));
        assertThat(NameTag.yawToward(new Point3d(0, 1.6, -5), new Point3d(0, 2, 0)))
                .as("and one behind it turns it half way round, rather than going edge-on"
                        + " and vanishing")
                .isCloseTo(Math.PI, within(1e-9));
    }

    @Test
    void onlyTheHorizontalPlaneCounts() {
        // The same bearing above and below the card must give the same yaw, or a
        // tag read from a staircase is a foreshortened smudge rather than text.
        Point3d anchor = new Point3d(0, 2, 0);
        assertThat(NameTag.yawToward(new Point3d(3, 9, 4), anchor))
                .isCloseTo(NameTag.yawToward(new Point3d(3, -4, 4), anchor), within(1e-12));
    }

    @Test
    void anEyeExactlyAtTheCardKeepsTheWayItWasDrawn() {
        Point3d at = new Point3d(1, 2, 3);
        assertThat(NameTag.yawToward(at, at)).isZero();
    }
}
