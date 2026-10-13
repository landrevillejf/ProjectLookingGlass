/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the input/output level meter, in pure
 * Java.
 *
 * The 2006 client painted two VU meters (microphone and speaker) and raised
 * the "microphoneGainTooHigh" warning; all of that lived in the external
 * softphone process and in JMF. The strings the listeners received were
 * already the contract — vuMeterValue("Microphone level: ...") — so the port
 * recomputes the level from the PCM the Java Sound sink captures and formats
 * the same vocabulary. The arithmetic is a peak follower with exponential
 * decay: |sample| maxes over the buffer, a hot peak near full scale flags
 * the gain warning, and idle slides toward silence so the needle falls.
 *
 * Deliberately clock-free: decay is per-fed-buffer, not per-wall-clock-ms,
 * because Java Sound hands buffers on a steady 20 ms cadence — one poll per
 * buffer — so the tests can feed exact sample arrays and assert exact peaks.
 */
package org.jdesktop.wonderland.modules.softphone;

/** Peak-with-decay level meter over signed 16-bit PCM. */
public final class VuMeter {

    /** Full scale of the input domain. */
    public static final double FULL_SCALE = 32768.0;
    /** Peak ratio at (or above) which the 2006 gain warning fires: a source
     *  this hot is clipping, and the listener should ask the user to turn
     *  the input down. */
    public static final double GAIN_TOO_HIGH_RATIO = 0.90;
    /** Per-update multiply that slides an idle peak toward zero; at 50
     *  buffers/second (20 ms frames) this is the classic ~0.3 s needle fall. */
    public static final double DECAY = 0.75;

    /** The meter names the 2006 listeners used verbatim. */
    public static final String MICROPHONE = "Microphone";
    public static final String SPEAKER = "Speaker";

    private final String device;
    private double peak;
    private boolean gainTooHigh;

    /** @param device one of {@link #MICROPHONE} / {@link #SPEAKER} */
    public VuMeter(String device) {
        this.device = device;
    }

    public String device() {
        return device;
    }

    /**
     * Feed one PCM buffer: the new peak is max(decayed peak, buffer peak).
     *
     * @return the level ratio 0.0..1.0 after this update
     */
    public double update(short[] samples) {
        double bufferPeak = 0.0;
        for (short sample : samples) {
            double magnitude = sample == Short.MIN_VALUE
                    ? FULL_SCALE : Math.abs((int) sample);
            if (magnitude > bufferPeak) {
                bufferPeak = magnitude;
            }
        }
        peak = Math.max(peak * DECAY, bufferPeak / FULL_SCALE);
        if (peak >= GAIN_TOO_HIGH_RATIO) {
            gainTooHigh = true;   // latched: the 2006 warning was edge-driven,
        }                         // not a per-buffer nag
        return peak;
    }

    /** The current level ratio, 0.0..1.0. */
    public double level() {
        return peak;
    }

    /** The same level in the 0..100 percent vocabulary the 2006 UI showed. */
    public int percent() {
        return (int) Math.round(peak * 100.0);
    }

    /** True once the input has run hot enough to clip; see {@link
     *  #GAIN_TOO_HIGH_RATIO}. Resets with the meter. */
    public boolean isGainTooHigh() {
        return gainTooHigh;
    }

    /** The listener string, in the 2006 vocabulary: "Microphone level: 42%". */
    public String valueString() {
        return device + " level: " + percent() + "%";
    }

    /** Forget the level and the latched warning (call teardown). */
    public void reset() {
        peak = 0.0;
        gainTooHigh = false;
    }
}
