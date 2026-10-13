/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: one sampled value channel of a keyframe clip.
 *
 * Times ascend, values are parallel; sampling interpolates between the
 * bracketing keys. COLLADA names LINEAR, BEZIER and NONE as interpolation
 * algorithms; the port ships LINEAR and NONE (held as STEP here). BEZIER
 * collapses to LINEAR at the factory -- the in/out tangent handles live in
 * a <technology_common> extension the parser deliberately does not read,
 * and a wrong curve is worse than a straight one; the collapse is the
 * factory's documented decision, not something this class hides.
 *
 * Out-of-range reads clamp to the end keys instead of wrapping: looping is
 * the AvatarAnimationDirector's policy (a clip may play once or loop), so a
 * track that wrapped on its own would double-loop.
 */
package org.jdesktop.wonderland.renderer.animation;

/** A time-sorted (times, values) pair with linear or step sampling. */
public final class KeyframeTrack {

    /** LINEAR interpolates between keys; STEP holds the previous key's value. */
    public enum Interpolation { LINEAR, STEP }

    private final double[] times;
    private final double[] values;
    private final Interpolation interpolation;

    public KeyframeTrack(double[] times, double[] values, Interpolation interpolation) {
        if (times.length != values.length) {
            throw new IllegalArgumentException(
                    "times/values length mismatch: " + times.length + " vs " + values.length);
        }
        this.times = times.clone();
        this.values = values.clone();
        this.interpolation = interpolation == null ? Interpolation.LINEAR : interpolation;
    }

    public int keyCount() {
        return times.length;
    }

    public Interpolation interpolation() {
        return interpolation;
    }

    /** The first key's time (0 for a factory-normalised track). */
    public double startTime() {
        return times.length == 0 ? 0.0 : times[0];
    }

    /** The last key's time; a one-key track has no duration and always returns it. */
    public double endTime() {
        return times.length == 0 ? 0.0 : times[times.length - 1];
    }

    /** The single value when there is exactly one key, else NaN-ish first/last fallback. */
    public double constantValue() {
        return values.length == 0 ? 0.0 : values[0];
    }

    /**
     * Sample at absolute track time {@code t}: clamped outside the key span,
     * linear between keys, previous-key hold under STEP until the next key
     * time is reached. Sampling exactly AT a key returns that key's value for
     * both interpolations (right-continuous), so duplicate timestamps make
     * the later key win. A zero-key track samples 0 (the channel simply
     * contributes nothing).
     */
    public double sampleAt(double t) {
        if (times.length == 0) {
            return 0.0;
        }
        if (t <= times[0]) {
            return values[0];
        }
        if (t >= times[times.length - 1]) {
            return values[values.length - 1];
        }
        int hi = 1;
        while (times[hi] <= t) {
            hi++;
        }
        int lo = hi - 1;
        if (interpolation == Interpolation.STEP) {
            return values[lo];
        }
        double span = times[hi] - times[lo];
        if (span <= 0.0) { // duplicate timestamps: the later key wins
            return values[hi];
        }
        double f = (t - times[lo]) / span;
        return values[lo] + f * (values[hi] - values[lo]);
    }
}
