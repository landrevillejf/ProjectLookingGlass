/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the four audio-quality presets of the
 * 2006 client, ported value-for-value from the decompiled AudioQuality enum
 * (wonderland-client.jar!/org/jdesktop/wonderland/client/softphone/).
 *
 * The 2006 preset drove the external softphone process ("quality=<name>" on
 * its stdin) AND the in-process JMF audio format. The port keeps the same
 * four presets: capture rate / channels describe what Java Sound opens in
 * the lg3d-apps sink; transmit rate / channels describe what goes on the
 * wire. Every preset transmits mono (G.711 is a 8 kHz mono codec; the
 * 2006 "stereo" presets captured stereo and mixed down to one transmit
 * channel -- the capture-side stereo was always just convenience for the
 * VU meter), which is why transmitChannels is 1 everywhere.
 */
package org.jdesktop.wonderland.modules.softphone;

/** The softphone audio presets, identical to the 2006 vocabulary. */
public enum AudioQuality {

    /** Minimum (8k mono) -- the classic G.711 telephony profile. */
    MINIMUM(8000, 1, 8000, 1, "Minimum (8k mono)"),
    /** Low (8k stereo) -- stereo capture, mono transmit. */
    STEREO(8000, 2, 8000, 1, "Low (8k stereo)"),
    /** Normal (16k stereo). */
    VPN(16000, 2, 16000, 1, "Normal (16k stereo)"),
    /** High (44.1k stereo). */
    BEST(44100, 2, 44100, 1, "High (44.1k stereo)");

    private final int sampleRate;
    private final int channels;
    private final int transmitSampleRate;
    private final int transmitChannels;
    private final String description;

    AudioQuality(int sampleRate, int channels, int transmitSampleRate,
                 int transmitChannels, String description) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.transmitSampleRate = transmitSampleRate;
        this.transmitChannels = transmitChannels;
        this.description = description;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int transmitSampleRate() {
        return transmitSampleRate;
    }

    public int transmitChannels() {
        return transmitChannels;
    }

    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }

    /**
     * The preset named {@code name}, case-insensitively, or null. The wire /
     * stdin vocabulary uses the enum constant names ("quality=VPN"); an
     * unknown name is the caller's problem to report -- the 2006 client
     * ignored a bad quality line, and so does this port.
     */
    public static AudioQuality parse(String name) {
        if (name == null) {
            return null;
        }
        for (AudioQuality q : values()) {
            if (q.name().equalsIgnoreCase(name.trim())) {
                return q;
            }
        }
        return null;
    }
}
