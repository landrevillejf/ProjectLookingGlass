/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: where a sound is, from the listener's seat.
 *
 * The 2006 client's audio nodes carried an origin and a radius on their cell
 * transforms; what the roadmap asks to rebuild is the judgement between
 * "a cell holds a sound" and "this frame that sound reaches the ear at this
 * volume and this side". That judgement is pure arithmetic -- distance to
 * inverse-gain, the listener's facing to a left/right pan -- so it lives here
 * as a static function over plain numbers, testable headless the way
 * OctantCuller and WalkerCollision are, with no mixer and no sound card in
 * sight.
 *
 * The conventions, stated once because every test below depends on them:
 * yaw is measured in radians from +Z toward +X (the same vocabulary the
 * shadow projector's yaw and the camera rig's heading use), so a listener
 * with yaw 0 looks down +Z and its right ear sits at +X; a sound straight
 * ahead pans 0, one at the right ear pans +1, one behind pans 0 again but
 * only after the distance model has had its say. Gain is the OpenAL
 * inverse-distance model the 2006 backend defaulted to: the reference
 * distance marks where the authored gain is delivered whole, gain falls as
 * refDistance / distance beyond it, and a sound past its maximum distance is
 * silent rather than faint -- the way OpenAL clipped it, and the reason an
 * avatar walking out of earshot stops being audible instead of becoming a
 * permanent whisper.
 */
package org.jdesktop.wonderland.client.audio;

/**
 * The headless half of spatial audio (roadmap P4.1): what a listener at one
 * pose hears of a source at another. No Java Sound, no JOAL, no display.
 */
public final class SpatialAudio {

    /** Below this distance two points are the same place; guards the divide. */
    private static final double EPS = 1e-6;

    /**
     * The listener's ear separation folded into the pan: a sound one ear's
     * width to the side is fully panned. 0.1 m is the physical head, not a
     * tuned constant -- the 2006 OpenAL context did its own distance-attenuated
     * separation, and this is the simplest honest stand-in for it.
     */
    private static final double EAR_RADIUS_M = 0.1;

    private SpatialAudio() {
    }

    /** Where the ear is and which way it faces. */
    public record Listener(float x, float y, float z, float yawRad) {
    }

    /** Where a sound sits and how far the host says it carries. */
    public record Source(float x, float y, float z,
            float refDistance, float maxDistance, float baseGain) {
    }

    /**
     * What one frame delivers to the mixer: a linear gain in {@code [0, 1]}
     * (already folded with the source's own base gain) and a pan in
     * {@code [-1, 1]} (negative left, positive right). A gain of zero is the
     * one honest "do not touch the volume control this frame".
     */
    public record Mix(float gain, float pan) {

        public boolean audible() {
            return gain > 0f;
        }
    }

    /**
     * The OpenAL inverse-distance gain, clamped exactly where OpenAL clamped
     * it: whole {@code baseGain} inside the reference distance, falling as
     * ref/d beyond it, zero at or past the maximum distance. A non-positive
     * reference or base gain answers zero -- a source that cannot carry at
     * all is silent, not an exception the audio thread never sees.
     */
    public static float gainAt(double distance, float refDistance,
            float maxDistance, float baseGain) {
        if (refDistance <= 0f || baseGain <= 0f) {
            return 0f;
        }
        if (maxDistance > 0f && distance >= maxDistance) {
            return 0f;
        }
        double d = Math.max(distance, refDistance);
        double gain = baseGain * (refDistance / d);
        return (float) Math.min(gain, 1.0);
    }

    /**
     * The pan of a source from a listener's seat: the unit vector from ear to
     * sound, dotted with the ear's right direction and squeezed through the
     * ear radius, so a sound one head-width to the right sits at +1 and one
     * dead ahead (or dead behind, or dead above) at 0. A source on top of the
     * listener pans 0 -- there is no side to pick, and inventing one would
     * flutter between ears frame to frame.
     */
    public static float panOf(Listener listener, Source source) {
        double dx = source.x() - listener.x();
        double dz = source.z() - listener.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < EPS) {
            return 0f;
        }
        double forwardX = Math.sin(listener.yawRad());
        double forwardZ = Math.cos(listener.yawRad());
        // right is forward turned a quarter turn to the clock-wise side seen
        // from above: yaw 0 looks down +Z, so the right ear is +X, and yaw
        // pi/2 looks at +X with the right ear at -Z.
        double rightX = forwardZ;
        double rightZ = -forwardX;
        double cosToRight = ((dx / flat) * rightX + (dz / flat) * rightZ);
        double pan = cosToRight * Math.min(flat / EAR_RADIUS_M, 1.0);
        return (float) Math.max(-1.0, Math.min(1.0, pan));
    }

    /**
     * The whole judgement in one call: distance for the gain, bearing for the
     * pan. This is what a frame computes per active sound; the mixer half is
     * the backend's, not the kernel's.
     */
    public static Mix mix(Listener listener, Source source) {
        double dx = source.x() - listener.x();
        double dy = source.y() - listener.y();
        double dz = source.z() - listener.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new Mix(gainAt(distance, source.refDistance(),
                source.maxDistance(), source.baseGain()), panOf(listener, source));
    }
}
