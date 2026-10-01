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
package org.jdesktop.lg3d.apps.recorder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import javax.sound.sampled.AudioFormat;

/**
 * The AWT-free recording seam. Audio capture is genuinely native: the JDK's
 * {@code javax.sound.sampled} opens the default capture line and writes PCM WAV
 * on its own, so microphone recording needs no external tool. Screen (video)
 * capture has no in-JDK encoder, so - exactly like the audio player's codec
 * split and the Media Writer's {@code dd}/{@code xorriso} handoff - this backend
 * builds the precise {@code ffmpeg} (or {@code avconv}) command line that grabs
 * the X11 display, while the panel keeps the native {@code javax.sound} path for
 * audio.
 *
 * <p>Every method here is pure and side-effect free - no process is started, no
 * capture device is opened - so the whole command / format table is
 * unit-testable headless. The thin process launch and {@code TargetDataLine}
 * capture live in {@link RecorderPanel}, guarded like the desktop's other
 * external-command paths.</p>
 */
public final class RecorderBackend {

    /**
     * External screen recorders this backend knows how to drive, in preference
     * order. {@code ffmpeg} leads; {@code avconv} is the Debian fork.
     */
    public static final List<String> KNOWN_RECORDERS = List.of("ffmpeg", "avconv");

    /** The default X11 grab source (the first screen of the local display). */
    public static final String DEFAULT_SOURCE = ":0.0";

    /** The default frame rate for screen capture. */
    public static final int DEFAULT_FPS = 30;

    /** The default capture sample rate in Hz. */
    public static final int DEFAULT_SAMPLE_RATE = 44100;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private RecorderBackend() {
        // no instances
    }

    /**
     * The PCM signed 16-bit little-endian WAV format {@code javax.sound.sampled}
     * writes natively. Building an {@link AudioFormat} touches no device, so this
     * is safe to call headless.
     *
     * @param sampleRate the capture rate in Hz (clamped to 8000..192000)
     * @param channels   1 for mono, 2 for stereo (clamped to 1..2)
     * @return the linear-PCM format
     */
    public static AudioFormat wavFormat(int sampleRate, int channels) {
        int rate = Math.max(8000, Math.min(192000, sampleRate));
        int ch = Math.max(1, Math.min(2, channels));
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                rate, 16, ch, ch * 2, rate, false);
    }

    /**
     * Builds the command line that records the X11 display with {@code recorder}.
     * An optional microphone track is muxed in via PulseAudio when
     * {@code captureAudio} is set.
     *
     * @param recorder     the executable name (e.g. {@code ffmpeg})
     * @param output       the destination file path (typically {@code .mp4})
     * @param source       the X11 grab source (e.g. {@code :0.0})
     * @param resolution   a {@code WIDTHxHEIGHT} grab size, or null/blank for the
     *                     whole screen
     * @param fps          the frame rate (clamped to 1..120)
     * @param captureAudio true to also record the default microphone
     * @return the argument list, never null; empty when a required input is blank
     */
    public static List<String> screenCommand(String recorder, String output,
                                             String source, String resolution,
                                             int fps, boolean captureAudio) {
        if (recorder == null || recorder.isBlank()
                || output == null || output.isBlank()
                || source == null || source.isBlank()) {
            return List.of();
        }
        int rate = Math.max(1, Math.min(120, fps));
        List<String> cmd = new ArrayList<>();
        cmd.add(recorder.trim());
        cmd.add("-y");
        cmd.add("-f");
        cmd.add("x11grab");
        String res = normaliseResolution(resolution);
        if (res != null) {
            cmd.add("-video_size");
            cmd.add(res);
        }
        cmd.add("-framerate");
        cmd.add(Integer.toString(rate));
        cmd.add("-i");
        cmd.add(source.trim());
        if (captureAudio) {
            cmd.add("-f");
            cmd.add("pulse");
            cmd.add("-i");
            cmd.add("default");
        }
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("ultrafast");
        if (captureAudio) {
            cmd.add("-c:a");
            cmd.add("aac");
        }
        cmd.add(output.trim());
        return cmd;
    }

    /**
     * Validates a {@code WIDTHxHEIGHT} resolution string.
     *
     * @return the trimmed resolution, or null when it is blank or malformed
     */
    static String normaliseResolution(String resolution) {
        if (resolution == null) {
            return null;
        }
        String s = resolution.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        int x = s.indexOf('x');
        if (x <= 0 || x == s.length() - 1) {
            return null;
        }
        try {
            int w = Integer.parseInt(s.substring(0, x).trim());
            int h = Integer.parseInt(s.substring(x + 1).trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return s;
    }

    /**
     * A timestamped default file name such as {@code recording-20260101-120000.wav}.
     *
     * @param prefix the name prefix (blank falls back to {@code recording})
     * @param ext    the extension without a dot (blank falls back to {@code wav})
     * @return the file name, never null
     */
    public static String defaultFileName(String prefix, String ext) {
        String p = (prefix == null || prefix.isBlank()) ? "recording" : prefix.trim();
        String e = (ext == null || ext.isBlank()) ? "wav" : ext.trim();
        if (e.startsWith(".")) {
            e = e.substring(1);
        }
        return p + "-" + LocalDateTime.now().format(STAMP) + "." + e;
    }

    /** The lower-case file extension of {@code path} (no dot), or empty. */
    public static String extension(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String s = path.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String last = (slash >= 0) ? s.substring(slash + 1) : s;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) {
            return "";
        }
        return last.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The first candidate recorder {@code available} reports present, in
     * {@link #KNOWN_RECORDERS} order.
     *
     * @param available a probe (typically a PATH lookup) - may be null
     * @return the first available recorder, or empty when none is
     */
    public static Optional<String> firstAvailableRecorder(Predicate<String> available) {
        if (available == null) {
            return Optional.empty();
        }
        for (String recorder : KNOWN_RECORDERS) {
            if (available.test(recorder)) {
                return Optional.of(recorder);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the recorder to use: the {@code preferred} one when it is set and
     * available, otherwise the first available known recorder.
     *
     * @param preferred a user-chosen recorder (may be null/blank for auto)
     * @param available a probe for whether an executable is on the PATH
     * @return the recorder to drive, or empty when none is available
     */
    public static Optional<String> resolveRecorder(String preferred,
                                                   Predicate<String> available) {
        if (preferred != null && !preferred.isBlank()
                && available != null && available.test(preferred.trim())) {
            return Optional.of(preferred.trim());
        }
        return firstAvailableRecorder(available);
    }
}
