/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P3.1: the follow rig, the smoothing between frames.
 *
 * WorldViewer's eye today is authored, not followed: walkInside() parks it at
 * a person's height and keyboard steps move it (see its EYE_HEIGHT_M). The
 * roadmap's P3.1 asks for the other shape -- an eye that trails an avatar and
 * arrives SMOOTHLY instead of teleporting each frame. That arrival is a
 * differential equation with a closed-form answer per step, and the answer is
 * exactly exponential smoothing: eye += (desired - eye) * (1 - e^(-dt/tau)).
 * tau is the rig's lag in seconds; the step is frame-rate independent because
 * it folds dt, so 30 fps and 120 fps converge on the same curve -- the
 * property a hand-held probe cannot grade but a headless test can.
 *
 * Two deliberate stances beyond the maths. A teleport: smoothing a 400 m jump
 * is a 400 m smear, so a gap past the rig's teleport radius SNAPS -- the eye
 * arrives with the subject rather than catching up across the whole world.
 * And the output is an
 * EyeProbe.EyePose, not a rig-private type: the framing probe (P5.3) can
 * grade what this rig frames with no adapter, and headingYaw() is stated in
 * the same +Z-toward-+X vocabulary SpatialAudio pans in, so the audio
 * listener's ear is one call from the camera's own pose.
 *
 * The honesty clause, as ever on a visual phase: this decides where the eye
 * SHOULD be, frame by frame, entirely headless; moving a live Transform3D
 * each tick is the shell's wiring, and whether the ride feels good is a
 * pair of eyes and a keyboard, not CI.
 */
package org.jdesktop.wonderland.client.rig;

import org.jdesktop.wonderland.client.cull.EyeProbe;

/** An exponential-smoothing third-person follow rig (roadmap P3.1). */
public final class FollowRig {

    /** Where the rig's eye stands after the last update. */
    private float x;
    private float y;
    private float z;

    /** The subject's head the eye last looked at -- the framing target. */
    private float headX;
    private float headY;
    private float headZ;

    /** Whether the eye has ever been placed, or is still the constructor's zero. */
    private boolean placed;

    private final float offsetX;
    private final float offsetY;
    private final float offsetZ;
    private final float lagSec;
    private final float teleportRadius;
    private final float headHeight;

    /**
     * @param offsetX      the eye's world-axis offset from the subject; a
     *                     subject facing +Z is trailed by a negative offsetZ,
     *                     and offsetX/Y/Z together are the rig's seat -- the
     *                     rig trails, it does not orbit: orbiting needs the
     *                     subject's yaw, which cell transforms do not carry
     *                     into the client model yet
     * @param offsetY      eye height above the subject's origin
     * @param offsetZ      depth behind the subject, same axes
     * @param lagSec       the smoothing time constant: 0 is a locked-on rig,
     *                     larger is a lazier camera; a negative lag is
     *                     refused as the programmer error it is
     * @param teleportRadius  a desired gap past this snaps instead of smears
     * @param headHeight  the subject's origin is feet; this is where the
     *                     eye aims above it (1.6 m is WorldViewer's own
     *                     EYE_HEIGHT_M for a standing person)
     */
    public FollowRig(float offsetX, float offsetY, float offsetZ,
            float lagSec, float teleportRadius, float headHeight) {
        if (lagSec < 0f) {
            throw new IllegalArgumentException("a rig cannot anticipate the subject: lag >= 0");
        }
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.lagSec = lagSec;
        this.teleportRadius = Math.max(0f, teleportRadius);
        this.headHeight = headHeight;
    }

    /**
     * One frame: pull the eye toward (subject + offset) by the fraction the
     * step's clock allows. A zero or negative dt is a frame that did not
     * happen -- the eye holds its place exactly, no divide, no drift. Returns
     * the eye's new position so a shell can push it without another call.
     */
    public float[] update(float subjectX, float subjectY, float subjectZ, float dtSec) {
        headX = subjectX;
        headY = subjectY + headHeight;
        headZ = subjectZ;
        float wantX = subjectX + offsetX;
        float wantY = subjectY + offsetY;
        float wantZ = subjectZ + offsetZ;

        if (!placed) {
            snapTo(wantX, wantY, wantZ); // the first frame has no history to smear
            return new float[] {x, y, z};
        }
        double gap = Math.sqrt(sq(wantX - x) + sq(wantY - y) + sq(wantZ - z));
        if (gap > teleportRadius || lagSec == 0f || !Double.isFinite(gap)) {
            snapTo(wantX, wantY, wantZ); // a teleport, a locked rig, a broken pose
        } else if (dtSec > 0f) {
            double alpha = 1.0 - Math.exp(-dtSec / lagSec);
            x += (float) ((wantX - x) * alpha);
            y += (float) ((wantY - y) * alpha);
            z += (float) ((wantZ - z) * alpha);
        }
        return new float[] {x, y, z};
    }

    private void snapTo(float nx, float ny, float nz) {
        x = nx;
        y = ny;
        z = nz;
        placed = true;
    }

    /** Park the rig by hand (the shell's "cut to this view" -- no smear, no lag). */
    public void place(float eyeX, float eyeY, float eyeZ) {
        snapTo(eyeX, eyeY, eyeZ);
    }

    /**
     * The framing-grade answer for this pose: an EyeProbe eye at the rig's
     * position looking at the subject's head with the given vertical field.
     * A degenerate head-on-eye look keeps the last heading rather than
     * normalising a zero vector -- EyeProbe frames nothing on a zero-length
     * direction, and repeating that here would be a rig that goes blind when
     * the subject walks into the camera.
     */
    public EyeProbe.EyePose eyePose(float fovDeg) {
        float toX = headX - x;
        float toY = headY - y;
        float toZ = headZ - z;
        float len = (float) Math.sqrt(sq(toX) + sq(toY) + sq(toZ));
        if (len <= 1e-6f) {
            toZ = 1f; // the documented hold: stare down +Z until there is a real aim
            toX = 0f;
            toY = 0f;
        } else {
            toX /= len;
            toY /= len;
            toZ /= len;
        }
        return new EyeProbe.EyePose(x, y, z, toX, toY, toZ, fovDeg);
    }

    /**
     * The heading the eye's aim implies on the ground plane, in the yaw
     * vocabulary of SpatialAudio.Listener (radians from +Z toward +X) -- the
     * one call that lets the audio ear ride the camera with no second maths.
     */
    public float headingYaw() {
        EyeProbe.EyePose pose = eyePose(60f);
        return (float) Math.atan2(pose.dirX(), pose.dirZ());
    }

    /** The eye's current position, for tests and for a shell's Transform3D. */
    public float[] position() {
        return new float[] {x, y, z};
    }

    private static double sq(float v) {
        return (double) v * v;
    }

    /** A one-line truth for a shell's debug HUD. */
    @Override
    public String toString() {
        return "FollowRig[eye=(" + x + ", " + y + ", " + z + ")"
                + " lag=" + lagSec + "s" + (placed ? "" : " unplaced") + "]";
    }
}
