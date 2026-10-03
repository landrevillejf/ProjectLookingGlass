/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.tuner;

/**
 * A time-domain fundamental-frequency estimator using the <b>YIN</b> algorithm
 * (de Cheveign&eacute; &amp; Kawahara, 2002), the standard autocorrelation-based
 * pitch detector for monophonic instrument tuning.
 *
 * <p>It runs entirely on a {@code float[]} sample buffer - no AWT, no audio
 * device, no threads, no Java&nbsp;3D - so a detector can be fed a synthetic
 * sine wave of a known frequency and asserted on headless. The microphone
 * capture that fills those buffers lives in {@link TunerPanel}.</p>
 *
 * <p>The five YIN steps are implemented directly:</p>
 * <ol>
 *   <li><b>Difference function</b> {@code d(tau) = sum_j (x[j] - x[j+tau])^2}.</li>
 *   <li><b>Cumulative mean normalised difference</b> so the value is scale-free:
 *       {@code d'(tau) = d(tau) / ((1/tau) * sum_{k<=tau} d(k))}, with
 *       {@code d'(0) = 1}.</li>
 *   <li><b>Absolute threshold</b>: the first lag whose {@code d'} drops below a
 *       threshold, refined down to the following local minimum.</li>
 *   <li><b>Parabolic interpolation</b> around that lag for sub-sample accuracy.</li>
 *   <li><b>Frequency</b> {@code f0 = sampleRate / tau}.</li>
 * </ol>
 *
 * <p>The lag search is bounded to the guitar/bass range
 * ({@value #MIN_FREQUENCY}..{@value #MAX_FREQUENCY} Hz), which also caps the
 * O(W&sup2;) work, and a low RMS noise floor rejects silence so a quiet room
 * never reports a spurious pitch.</p>
 */
public final class PitchDetector {

    /** The default CMNDF absolute threshold below which a lag is periodic. */
    public static final float DEFAULT_THRESHOLD = 0.15f;

    /**
     * The lowest detectable fundamental, in Hz. Chosen to cover a five-string
     * bass low B (30.87 Hz) with margin.
     */
    public static final float MIN_FREQUENCY = 30.0f;

    /**
     * The highest detectable fundamental, in Hz. Above any guitar/bass open
     * string (high E4 = 329.63 Hz), leaving headroom for fretted notes.
     */
    public static final float MAX_FREQUENCY = 1200.0f;

    /**
     * The default RMS level below which a buffer is treated as silence and no
     * pitch is reported (roughly -46 dBFS on a normalised [-1, 1] signal).
     */
    public static final float DEFAULT_NOISE_FLOOR = 0.005f;

    /** A CMNDF above this, even at the global minimum, is not periodic enough. */
    private static final float UNVOICED_CMNDF = 0.5f;

    private final int sampleRate;
    private final float threshold;
    private final float noiseFloor;
    private final int minTau;
    private final int maxTau;

    // Reusable scratch so a live detector does not allocate on every frame.
    private final float[] difference;
    private final float[] cmndf;

    /**
     * Builds a detector for a sample rate with the default threshold and noise
     * floor.
     *
     * @param sampleRate the audio sample rate in Hz (must be positive)
     */
    public PitchDetector(int sampleRate) {
        this(sampleRate, DEFAULT_THRESHOLD, DEFAULT_NOISE_FLOOR);
    }

    /**
     * Builds a detector with an explicit periodicity threshold and noise floor.
     *
     * @param sampleRate the audio sample rate in Hz (must be positive)
     * @param threshold  the CMNDF absolute threshold (clamped to 0.01..0.5)
     * @param noiseFloor the RMS level below which a buffer is silence (>= 0)
     */
    public PitchDetector(int sampleRate, float threshold, float noiseFloor) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        this.sampleRate = sampleRate;
        this.threshold = Math.max(0.01f, Math.min(0.5f, threshold));
        this.noiseFloor = Math.max(0f, noiseFloor);
        // Lag bounds from the frequency range: a low frequency is a long lag.
        this.minTau = Math.max(2, (int) Math.floor(sampleRate / MAX_FREQUENCY));
        this.maxTau = Math.max(minTau + 1, (int) Math.ceil(sampleRate / MIN_FREQUENCY));
        this.difference = new float[maxTau + 1];
        this.cmndf = new float[maxTau + 1];
    }

    /** The sample rate this detector was built for, in Hz. */
    public int getSampleRate() {
        return sampleRate;
    }

    /** The lowest lag (in samples) searched, derived from {@link #MAX_FREQUENCY}. */
    public int getMinTau() {
        return minTau;
    }

    /** The highest lag (in samples) searched, derived from {@link #MIN_FREQUENCY}. */
    public int getMaxTau() {
        return maxTau;
    }

    /**
     * Estimates the fundamental frequency of a monophonic buffer.
     *
     * @param buffer samples normalised to [-1, 1]; the analysis window is the
     *               first {@code min(buffer.length, 2 * maxTau)} samples
     * @return a {@link Pitch}; unvoiced when the buffer is null/short, silent,
     *         or not periodic enough
     */
    public Pitch detect(float[] buffer) {
        if (buffer == null) {
            return Pitch.unvoiced();
        }
        // Decouple the integration window from the lag search. The difference
        // function reads x[j + tau] for j < window and tau <= maxLag, so it needs
        // window + maxLag <= buffer.length. Using half the buffer as the
        // integration window (rather than capping it at maxTau) averages over more
        // samples, which sharpens the estimate on low bass strings.
        int window = buffer.length / 2;
        int maxLag = Math.min(maxTau, window);
        if (maxLag < minTau || window + maxLag > buffer.length) {
            return Pitch.unvoiced();
        }
        if (rms(buffer) < noiseFloor) {
            return Pitch.unvoiced();
        }

        differenceFunction(buffer, window, maxLag);
        cumulativeMeanNormalizedDifference(maxLag);

        int tau = absoluteThreshold(maxLag);
        if (tau < 0) {
            return Pitch.unvoiced();
        }
        float clarity = Math.max(0f, Math.min(1f, 1f - cmndf[tau]));
        float preciseTau = parabolicInterpolation(tau, maxLag);
        if (preciseTau <= 0f) {
            return Pitch.unvoiced();
        }
        float frequency = sampleRate / preciseTau;
        if (frequency < MIN_FREQUENCY * 0.5f || frequency > MAX_FREQUENCY * 2f) {
            return Pitch.unvoiced();
        }
        return new Pitch(frequency, clarity);
    }

    /**
     * Step 1: the raw squared difference function over lags {@code 1..maxLag},
     * each summed across the {@code window}-sample integration window.
     */
    private void differenceFunction(float[] x, int window, int maxLag) {
        difference[0] = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            float sum = 0f;
            for (int j = 0; j < window; j++) {
                float delta = x[j] - x[j + tau];
                sum += delta * delta;
            }
            difference[tau] = sum;
        }
    }

    /** Step 2: the cumulative mean normalised difference over lags 0..maxLag. */
    private void cumulativeMeanNormalizedDifference(int maxLag) {
        cmndf[0] = 1f;
        float runningSum = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            runningSum += difference[tau];
            cmndf[tau] = (runningSum == 0f) ? 1f : (difference[tau] * tau) / runningSum;
        }
    }

    /**
     * Step 3: the first lag below the threshold, refined to the local minimum
     * that follows it; falls back to the global minimum when nothing crosses the
     * threshold, and reports -1 when even that is not periodic enough.
     */
    private int absoluteThreshold(int maxLag) {
        for (int tau = minTau; tau <= maxLag; tau++) {
            if (cmndf[tau] < threshold) {
                while (tau + 1 <= maxLag && cmndf[tau + 1] < cmndf[tau]) {
                    tau++;
                }
                return tau;
            }
        }
        int best = -1;
        float bestValue = Float.MAX_VALUE;
        for (int tau = minTau; tau <= maxLag; tau++) {
            if (cmndf[tau] < bestValue) {
                bestValue = cmndf[tau];
                best = tau;
            }
        }
        return (bestValue > UNVOICED_CMNDF) ? -1 : best;
    }

    /**
     * Step 4: parabolic interpolation around the chosen lag for sub-sample
     * accuracy. Returns the (possibly fractional) lag, or the integer lag when
     * the neighbours are unavailable or degenerate.
     */
    private float parabolicInterpolation(int tau, int maxLag) {
        if (tau <= 0 || tau >= maxLag) {
            return tau;
        }
        float alpha = cmndf[tau - 1];
        float beta = cmndf[tau];
        float gamma = cmndf[tau + 1];
        float denominator = alpha - 2f * beta + gamma;
        if (denominator == 0f) {
            return tau;
        }
        float delta = 0.5f * (alpha - gamma) / denominator;
        // Guard against a wild interpolation step.
        if (delta < -1f || delta > 1f) {
            return tau;
        }
        return tau + delta;
    }

    /** The root-mean-square level of the buffer (a cheap silence gate). */
    private static float rms(float[] x) {
        if (x.length == 0) {
            return 0f;
        }
        double sum = 0.0;
        for (float v : x) {
            sum += (double) v * v;
        }
        return (float) Math.sqrt(sum / x.length);
    }

    /**
     * The result of one detection: a fundamental frequency in Hz and a
     * periodicity confidence in [0, 1]. An unvoiced result has a non-positive
     * frequency and {@link #isVoiced()} returns false.
     */
    public static final class Pitch {

        private final float frequency;
        private final float clarity;

        /**
         * Builds a voiced pitch result.
         *
         * @param frequency the fundamental frequency in Hz (positive)
         * @param clarity   the periodicity confidence in [0, 1]
         */
        public Pitch(float frequency, float clarity) {
            this.frequency = frequency;
            this.clarity = Math.max(0f, Math.min(1f, clarity));
        }

        /** The shared unvoiced (no clear pitch) result. */
        public static Pitch unvoiced() {
            return UNVOICED;
        }

        private static final Pitch UNVOICED = new Pitch(-1f, 0f);

        /** The detected fundamental frequency in Hz, or negative when unvoiced. */
        public float getFrequency() {
            return frequency;
        }

        /** The periodicity confidence in [0, 1]; higher is a clearer tone. */
        public float getClarity() {
            return clarity;
        }

        /** Whether a clear pitch was found. */
        public boolean isVoiced() {
            return frequency > 0f;
        }

        @Override
        public String toString() {
            return isVoiced()
                    ? String.format("%.2f Hz (clarity %.2f)", frequency, clarity)
                    : "unvoiced";
        }
    }
}
