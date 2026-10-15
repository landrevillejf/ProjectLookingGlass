/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P3.1: the follow rig, headless.
 *
 * These asserts are statements about a curve, not a screen: one time constant
 * per second converges to a fixed fraction no matter how the seconds were
 * sliced, the eye closes on the subject without crossing past it, a teleport
 * arrives instead of smearing, and a paused clock freezes the frame. What no
 * test here claims: that the live ride LOOKS smooth -- moving a real
 * Transform3D per tick is the shell's wiring and the probe's judgement.
 */
package org.jdesktop.wonderland.client.rig;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FollowRigTest {

    /** Seat 2 m up and 4 m back along -Z, 1 s lag, 25 m teleport radius, head 1.6 m. */
    private static FollowRig rig() {
        return new FollowRig(0f, 2f, -4f, 1f, 25f, 1.6f);
    }

    @Test
    void theFirstFramePlacesTheEyeAtTheSeatRatherThanSmearingFromTheOrigin() {
        FollowRig rig = rig();

        float[] eye = rig.update(0, 0, 0, 1f / 60f);

        assertThat(eye).containsExactly(0f, 2f, -4f);
    }

    @Test
    void theEyeClosesOnTheSubjectAndNeverOvershootsTheSeat() {
        FollowRig rig = rig();
        rig.place(0, 2, 0); // subject walks away to z = 10; seat is now z = 6
        rig.update(10, 0, 10, 0f); // (dt 0 above is the hold test; move the target)
        double previous = Math.abs(rig.position()[2] - 6);

        for (int frame = 0; frame < 600; frame++) {
            float[] eye = rig.update(10, 0, 10, 1f / 60f);
            double distance = Math.abs(eye[2] - 6);
            assertThat(distance).as("monotone approach, no overshoot past the seat")
                    .isLessThanOrEqualTo(previous + 1e-6);
            previous = distance;
        }

        assertThat(previous).isLessThan(1e-3);
    }

    @Test
    void oneTimeConstantPerSecondConvergesTo63PercentWhateverTheFrameSlice() {
        // Exponential truth: after exactly one lag (1 s), the gap has fallen to
        // e^-1 of its value -- whether that second arrived as 60 frames or as
        // one big stall. Frame-rate independence is the whole reason the step
        // folds dt through an exponential instead of a fixed lerp factor.
        FollowRig slow = rig();
        FollowRig fast = rig();
        slow.place(0, 2, 0);
        fast.place(0, 2, 0);

        slow.update(10, 0, 10, 1f); // one stall of a full second
        for (int frame = 0; frame < 60; frame++) {
            fast.update(10, 0, 10, 1f / 60f);
        }

        assertThat(slow.position()).containsExactly(fast.position(), within(1e-3f));
        // and the gap really did close to 1/e, not some other fraction:
        double remaining = Math.abs(fast.position()[2] - 6);
        assertThat(remaining).isCloseTo(6.0 * Math.exp(-1.0), within(0.02));
    }

    @Test
    void aTeleportedSubjectArrivesWithTheEyeInsteadOfSmearingAcrossTheWorld() {
        FollowRig rig = rig();
        rig.update(0, 0, 0, 1f / 60f);

        rig.update(500, 0, 500, 1f / 60f); // cell detached and re-attached far away

        assertThat(rig.position())
                .as("the seat, immediately -- a frame of lag on 1000 m is a streak")
                .containsExactly(500f, 2f, 496f); // a snap assigns the seat exactly
    }

    @Test
    void aPausedClockFreezesTheEyeExactly() {
        FollowRig rig = rig();
        rig.update(0, 0, 0, 1f / 60f);
        rig.update(10, 0, 10, 1f / 60f);
        float[] moved = rig.position();

        assertThat(rig.update(10, 0, 10, 0f)).containsExactly(moved[0], moved[1], moved[2]);
        assertThat(rig.update(10, 0, 10, -1f))
                .as("a broken clock is treated like a paused one, not a reverse")
                .containsExactly(moved[0], moved[1], moved[2]);
    }

    @Test
    void aZeroLagIsALockedCameraNotADivideByZero() {
        FollowRig locked = new FollowRig(0f, 2f, -4f, 0f, 25f, 1.6f);
        locked.update(0, 0, 0, 1f / 60f);

        float[] eye = locked.update(3, 0, 3, 1f / 60f);

        assertThat(eye).containsExactly(3f, 2f, -1f);
    }

    @Test
    void thePoseLooksAtTheHeadAndEarRidesTheHeading() {
        FollowRig rig = rig();
        rig.update(0, 0, 10, 1f / 60f); // subject at z=10, eye at z=6, head at 1.6

        var pose = rig.eyePose(60f);

        assertThat(Math.sqrt(pose.dirX() * pose.dirX()
                + pose.dirY() * pose.dirY() + pose.dirZ() * pose.dirZ()))
                .as("a unit look direction, whatever the geometry")
                .isCloseTo(1.0, within(1e-5));
        assertThat(pose.dirZ()).isGreaterThan(0.99f); // nearly straight down +Z
        assertThat(pose.y()).isEqualTo(2f);
        assertThat(rig.headingYaw())
                .as("heading 0 is +Z, the SpatialAudio convention")
                .isCloseTo(0f, within(1e-4f));
    }

    @Test
    void aNegativeLagIsRefusedAsTheProgrammerErrorItIs() {
        assertThatThrownBy(() -> new FollowRig(0, 2, -4, -1f, 25f, 1.6f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSubjectWalkingIntoTheEyeKeepsARealHeadingRatherThanGoingBlind() {
        FollowRig rig = rig();
        rig.place(0, 1.6f, 0); // park the eye exactly on the subject's head point
        rig.update(0, 0, 0, 0f); // head is at (0, 1.6, 0) == the eye, dt frozen

        var pose = rig.eyePose(60f);

        assertThat(pose.dirX()).isZero();
        assertThat(pose.dirZ()).as("the documented +Z hold beats a NaN normalise")
                .isEqualTo(1f);
    }

    @Test
    void aBrokenPoseSubjectSnapsAndTheNextRealFrameRecoversExactly() {
        // A NaN subject position makes the gap NaN, and every NaN comparison
        // is false -- a smoothing step would fold the NaN into the eye's
        // history and the rig would be blind FOREVER. The isFinite guard
        // routes it through the snap instead: the frame is lost, the camera
        // is not, and one sane pose later the seat is exact again.
        FollowRig rig = rig();
        rig.update(0, 0, 0, 1f / 60f);

        float[] broken = rig.update(Float.NaN, 0, 0, 1f / 60f);
        assertThat(broken[0]).as("the seat inherited the broken number, once").isNaN();

        assertThat(rig.update(3, 0, 3, 1f / 60f))
                .as("one real pose later the eye is back on the seat exactly")
                .containsExactly(3f, 2f, -1f);
    }

    @Test
    void theHudLineTellsTheTruthBeforeAndAfterTheFirstFrame() {
        // toString is the shell's one-line diagnostic; both of its states are
        // promises -- an unplaced rig says so rather than printing (0,0,0),
        // and a placed one prints the eye the next frame will start from.
        FollowRig rig = rig();
        assertThat(rig.toString()).contains("unplaced");

        rig.update(0, 0, 10, 1f / 60f);

        assertThat(rig.toString())
                .isEqualTo("FollowRig[eye=(0.0, 2.0, 6.0) lag=1.0s]");
    }
}
