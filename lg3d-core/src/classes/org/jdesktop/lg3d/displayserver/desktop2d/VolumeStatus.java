/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.util.Optional;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.Control;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.Port;

/**
 * Master output volume for the 2D taskbar indicator.
 *
 * <p>The clamping and formatting are pure ({@link #clamp}, {@link #glyph},
 * {@link #label}) and unit-tested; the read/write methods are the thin platform
 * probe over the {@code javax.sound.sampled} master port. They return
 * {@link Optional#empty()} / no-op when no master control exists (headless CI,
 * no sound card), so the indicator hides itself instead of failing.</p>
 */
final class VolumeStatus {

    private VolumeStatus() {
        // no instances
    }

    /** A volume reading: master percentage (0-100) and mute state. */
    record Level(int percent, boolean muted) {
    }

    /** Clamps a percentage into 0-100. */
    static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /**
     * Converts a master-gain value in dB (between the control's min and max) to
     * a 0-100 percentage, linear in dB. Pure so it can be unit-tested without a
     * sound card.
     */
    static int percentFromDb(float value, float min, float max) {
        if (max <= min) {
            return 0;
        }
        float clamped = Math.max(min, Math.min(max, value));
        return Math.round((clamped - min) / (max - min) * 100f);
    }

    /** The inverse of {@link #percentFromDb}: a 0-100 percentage to dB. */
    static float dbFromPercent(int percent, float min, float max) {
        float clamped = clamp(percent) / 100f;
        return min + clamped * (max - min);
    }

    /** Reads the master volume, or empty when no master control is available. */
    static Optional<Level> read() {
        try {
            Port.Info info = findMasterPort();
            if (info == null) {
                return Optional.empty();
            }
            try (Line line = openLine(info)) {
                if (line == null) {
                    return Optional.empty();
                }
                FloatControl gain = floatControl(line, FloatControl.Type.MASTER_GAIN);
                BooleanControl mute = booleanControl(line, BooleanControl.Type.MUTE);
                int percent = (gain == null) ? 0
                        : percentFromDb(gain.getValue(), gain.getMinimum(), gain.getMaximum());
                boolean muted = mute != null && mute.getValue();
                return Optional.of(new Level(percent, muted));
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Sets the master volume (0-100); a no-op when no master control exists. */
    static void setVolume(int percent) {
        try {
            Port.Info info = findMasterPort();
            if (info == null) {
                return;
            }
            try (Line line = openLine(info)) {
                FloatControl gain =
                        (line == null) ? null : floatControl(line, FloatControl.Type.MASTER_GAIN);
                if (gain != null) {
                    gain.setValue(dbFromPercent(percent, gain.getMinimum(), gain.getMaximum()));
                }
            }
        } catch (RuntimeException e) {
            // best effort: a machine with no master control just ignores this
        }
    }

    /** Sets the master mute; a no-op when no master control exists. */
    static void setMuted(boolean muted) {
        try {
            Port.Info info = findMasterPort();
            if (info == null) {
                return;
            }
            try (Line line = openLine(info)) {
                BooleanControl mute =
                        (line == null) ? null : booleanControl(line, BooleanControl.Type.MUTE);
                if (mute != null) {
                    mute.setValue(muted);
                }
            }
        } catch (RuntimeException e) {
            // best effort
        }
    }

    private static Port.Info findMasterPort() {
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixerInfos) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                if (lineInfo instanceof Port.Info portInfo && portInfo.isSource()) {
                    return portInfo;
                }
            }
        }
        return null;
    }

    private static Line openLine(Port.Info info) {
        try {
            Line line = AudioSystem.getLine(info);
            line.open();
            return line;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return null;
        }
    }

    private static FloatControl floatControl(Line line, Control.Type type) {
        if (line instanceof Port port && port.isControlSupported(type)) {
            Control control = port.getControl(type);
            if (control instanceof FloatControl fc) {
                return fc;
            }
        }
        return null;
    }

    private static BooleanControl booleanControl(Line line, Control.Type type) {
        if (line instanceof Port port && port.isControlSupported(type)) {
            Control control = port.getControl(type);
            if (control instanceof BooleanControl bc) {
                return bc;
            }
        }
        return null;
    }

    /** Compact taskbar text: {@code "Vol x"} muted, {@code "Vol 42%"} otherwise, {@code "Vol --"} unknown. */
    static String glyph(Level level) {
        if (level == null) {
            return "Vol --";
        }
        if (level.muted()) {
            return "Vol x";
        }
        return "Vol " + level.percent() + "%";
    }

    /** Detailed tooltip text. */
    static String label(Level level) {
        if (level == null) {
            return "Volume: unavailable";
        }
        if (level.muted()) {
            return "Volume: muted";
        }
        return "Volume: " + level.percent() + "%";
    }
}
