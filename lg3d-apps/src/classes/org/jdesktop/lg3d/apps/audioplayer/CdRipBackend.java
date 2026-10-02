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
package org.jdesktop.lg3d.apps.audioplayer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The AWT-free CD-ripping seam. Ripping a physical audio CD is genuinely native
 * to no part of the JDK - there is no in-JDK CD-DA reader and no bundled MP3
 * encoder - so, exactly like {@link AudioBackend}'s codec split and
 * {@link RecorderBackend}'s {@code ffmpeg} handoff, this backend builds the
 * precise command lines for real external tools and leaves the thin, guarded
 * process launch to {@link AudioPlayerPanel}.
 *
 * <p>The pipeline is: read the disc TOC ({@code cdparanoia -Q}, fallback
 * {@code cd-info}) to learn the track count and offsets; extract each selected
 * track to WAV ({@code cdparanoia}, or {@code ffmpeg -f audiocd}); then, when the
 * user asked for something other than a straight 44.1&nbsp;kHz WAV, encode /
 * resample with {@code ffmpeg} (preferred) or {@code lame}.</p>
 *
 * <p>Every method here is pure and side-effect free - no process is started, no
 * device is opened - so the whole command / format table is unit-testable
 * headless. {@link #defaultDevice()} is the only method that touches the
 * filesystem, and only to test which device nodes exist; the pure
 * {@link #defaultDevice(Predicate)} variant is what the tests exercise.</p>
 */
public final class CdRipBackend {

    /** The output container / codec a rip targets. */
    public enum Format {
        /** PCM WAV (lossless, JDK-playable). */
        WAV("wav"),
        /** MPEG-1 Layer III (lossy, needs an external encoder). */
        MP3("mp3");

        private final String ext;

        Format(String ext) {
            this.ext = ext;
        }

        /** @return the lower-case file extension (no dot) for this format. */
        public String extension() {
            return ext;
        }
    }

    /** Rippers / TOC readers this backend knows how to drive, in preference order. */
    public static final List<String> KNOWN_RIPPERS =
            List.of("cdparanoia", "cd-info");

    /** Encoders this backend knows how to drive, in preference order. */
    public static final List<String> KNOWN_ENCODERS =
            List.of("ffmpeg", "avconv", "lame");

    /** Supported target sampling rates in Hz (CD-native is 44100). */
    public static final int[] SAMPLE_RATES =
            {8000, 11025, 16000, 22050, 32000, 44100, 48000};

    /** Supported MP3 bitrates in kbps. */
    public static final int[] MP3_BITRATES = {128, 192, 256, 320};

    /** The CD-native sampling rate, and this backend's default target. */
    public static final int DEFAULT_SAMPLE_RATE = 44100;

    /** The default MP3 bitrate in kbps. */
    public static final int DEFAULT_BITRATE = 192;

    /** Candidate CD device nodes, probed in order by {@link #defaultDevice()}. */
    public static final List<String> KNOWN_DEVICES =
            List.of("/dev/cdrom", "/dev/sr0", "/dev/sr1", "/dev/dvd");

    private CdRipBackend() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Value normalisation
    // ------------------------------------------------------------------

    /**
     * Snaps {@code rate} to a supported sampling rate; an unrecognised value
     * falls back to {@link #DEFAULT_SAMPLE_RATE}.
     */
    public static int normaliseSampleRate(int rate) {
        for (int r : SAMPLE_RATES) {
            if (r == rate) {
                return r;
            }
        }
        return DEFAULT_SAMPLE_RATE;
    }

    /**
     * Snaps {@code bitrate} to a supported MP3 bitrate; an unrecognised value
     * falls back to {@link #DEFAULT_BITRATE}.
     */
    public static int normaliseBitrate(int bitrate) {
        for (int b : MP3_BITRATES) {
            if (b == bitrate) {
                return b;
            }
        }
        return DEFAULT_BITRATE;
    }

    // ------------------------------------------------------------------
    // Device discovery
    // ------------------------------------------------------------------

    /**
     * The first candidate device {@code exists} reports present, in
     * {@link #KNOWN_DEVICES} order.
     *
     * @param exists a probe (typically {@code p -> new File(p).exists()}) - may
     *               be null
     * @return the first existing device node, or empty when none is
     */
    public static Optional<String> defaultDevice(Predicate<String> exists) {
        if (exists == null) {
            return Optional.empty();
        }
        for (String device : KNOWN_DEVICES) {
            if (exists.test(device)) {
                return Optional.of(device);
            }
        }
        return Optional.empty();
    }

    /** The first existing candidate CD device on this machine, or empty. */
    public static Optional<String> defaultDevice() {
        return defaultDevice(p -> p != null && new File(p).exists());
    }

    // ------------------------------------------------------------------
    // Command builders (pure)
    // ------------------------------------------------------------------

    /**
     * Builds the command that reads the disc TOC. {@code cdparanoia} prints the
     * TOC to stderr with {@code -Q}; {@code cd-info} prints it to stdout.
     *
     * @param ripper the ripper executable (e.g. {@code cdparanoia})
     * @param device the CD device node (blank means the tool's default)
     * @return the argument list, or empty when {@code ripper} is blank
     */
    public static List<String> readTocCommand(String ripper, String device) {
        if (ripper == null || ripper.isBlank()) {
            return List.of();
        }
        String r = ripper.trim();
        List<String> cmd = new ArrayList<>();
        cmd.add(r);
        if ("cd-info".equals(r)) {
            cmd.add("--no-header");
            cmd.add("--no-cddb");
            if (device != null && !device.isBlank()) {
                cmd.add("-C");
                cmd.add(device.trim());
            }
            return cmd;
        }
        // cdparanoia (and friends): -Q queries the TOC without ripping.
        if (device != null && !device.isBlank()) {
            cmd.add("-d");
            cmd.add(device.trim());
        }
        cmd.add("-Q");
        return cmd;
    }

    /**
     * Builds the command that extracts {@code track} from the disc to a WAV file.
     * {@code cdparanoia} is preferred (error-corrected); {@code ffmpeg -f audiocd}
     * is the fallback when cdparanoia is absent.
     *
     * @param ripper the ripper executable (e.g. {@code cdparanoia} / {@code ffmpeg})
     * @param device the CD device node (blank means the tool's default)
     * @param track  the 1-based track number to extract
     * @param outWav the destination {@code .wav} path
     * @return the argument list, or empty when a required input is blank or the
     *         track number is out of range
     */
    public static List<String> ripWavCommand(String ripper, String device,
                                             int track, String outWav) {
        if (ripper == null || ripper.isBlank()
                || outWav == null || outWav.isBlank() || track < 1) {
            return List.of();
        }
        String r = ripper.trim();
        String out = outWav.trim();
        List<String> cmd = new ArrayList<>();
        if ("ffmpeg".equals(r) || "avconv".equals(r)) {
            cmd.add(r);
            cmd.add("-y");
            cmd.add("-f");
            cmd.add("audiocd");
            cmd.add("-i");
            cmd.add((device == null || device.isBlank()) ? "/dev/cdrom" : device.trim());
            cmd.add("-track");
            cmd.add(Integer.toString(track));
            cmd.add("-c:a");
            cmd.add("pcm_s16le");
            cmd.add(out);
            return cmd;
        }
        // cdparanoia: <span> <outfile>; a bare track number spans the whole track.
        if (device != null && !device.isBlank()) {
            cmd.add(r);
            cmd.add("-d");
            cmd.add(device.trim());
        } else {
            cmd.add(r);
        }
        cmd.add(Integer.toString(track));
        cmd.add(out);
        return cmd;
    }

    /**
     * Builds the command that encodes / resamples {@code inWav} into
     * {@code outFile} in the requested {@code format}. WAV output only needs a
     * step when the target rate differs from the CD-native 44100&nbsp;Hz; MP3
     * always encodes. {@code lame} handles MP3 only - a WAV resample with
     * {@code lame} is unsupported and yields an empty command so the caller can
     * pick {@code ffmpeg}.
     *
     * @param encoder    the encoder executable (e.g. {@code ffmpeg} / {@code lame})
     * @param inWav      the source WAV path
     * @param outFile    the destination path
     * @param format     the target {@link Format}
     * @param sampleRate the target sampling rate in Hz (normalised internally)
     * @param bitrate    the MP3 bitrate in kbps (normalised internally; ignored
     *                   for WAV)
     * @return the argument list, or empty when a required input is blank or the
     *         encoder cannot produce the requested format
     */
    public static List<String> encodeCommand(String encoder, String inWav,
                                             String outFile, Format format,
                                             int sampleRate, int bitrate) {
        if (encoder == null || encoder.isBlank()
                || inWav == null || inWav.isBlank()
                || outFile == null || outFile.isBlank() || format == null) {
            return List.of();
        }
        String e = encoder.trim();
        String in = inWav.trim();
        String out = outFile.trim();
        int rate = normaliseSampleRate(sampleRate);
        int br = normaliseBitrate(bitrate);

        if ("lame".equals(e)) {
            if (format != Format.MP3) {
                // lame is an MP3 encoder only; it cannot emit / resample WAV.
                return List.of();
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(e);
            cmd.add("--resample");
            cmd.add(khz(rate));
            cmd.add("-b");
            cmd.add(Integer.toString(br));
            cmd.add(in);
            cmd.add(out);
            return cmd;
        }

        // ffmpeg / avconv (and anything else) share the same flag vocabulary.
        List<String> cmd = new ArrayList<>();
        cmd.add(e);
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(in);
        cmd.add("-ar");
        cmd.add(Integer.toString(rate));
        if (format == Format.MP3) {
            cmd.add("-c:a");
            cmd.add("libmp3lame");
            cmd.add("-b:a");
            cmd.add(br + "k");
        } else {
            cmd.add("-c:a");
            cmd.add("pcm_s16le");
        }
        cmd.add(out);
        return cmd;
    }

    /**
     * Expresses {@code rate} Hz in kHz with the fewest decimals {@code lame
     * --resample} accepts (44100 -&gt; {@code 44.1}, 22050 -&gt; {@code 22.05},
     * 48000 -&gt; {@code 48}).
     */
    static String khz(int rate) {
        double k = rate / 1000.0;
        String s = String.format(Locale.ROOT, "%.3f", k);
        // Trim trailing zeros then a trailing dot: 44.100 -> 44.1, 48.000 -> 48.
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Tool resolution
    // ------------------------------------------------------------------

    /**
     * The first candidate {@code available} reports present, in
     * {@code candidates} order.
     *
     * @param available  a probe (typically a PATH lookup) - may be null
     * @param candidates the executables to consider, in preference order
     * @return the first available candidate, or empty when none is
     */
    public static Optional<String> firstAvailable(Predicate<String> available,
                                                  List<String> candidates) {
        if (available == null || candidates == null) {
            return Optional.empty();
        }
        for (String tool : candidates) {
            if (tool != null && !tool.isBlank() && available.test(tool)) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    /** The first available ripper in {@link #KNOWN_RIPPERS} order. */
    public static Optional<String> firstAvailableRipper(Predicate<String> available) {
        return firstAvailable(available, KNOWN_RIPPERS);
    }

    /** The first available encoder in {@link #KNOWN_ENCODERS} order. */
    public static Optional<String> firstAvailableEncoder(Predicate<String> available) {
        return firstAvailable(available, KNOWN_ENCODERS);
    }

    /**
     * Resolves the ripper to use: {@code preferred} when set and available,
     * otherwise the first available known ripper.
     */
    public static Optional<String> resolveRipper(String preferred,
                                                 Predicate<String> available) {
        if (preferred != null && !preferred.isBlank()
                && available != null && available.test(preferred.trim())) {
            return Optional.of(preferred.trim());
        }
        return firstAvailableRipper(available);
    }

    /**
     * Resolves the encoder to use: {@code preferred} when set and available,
     * otherwise the first available known encoder.
     */
    public static Optional<String> resolveEncoder(String preferred,
                                                  Predicate<String> available) {
        if (preferred != null && !preferred.isBlank()
                && available != null && available.test(preferred.trim())) {
            return Optional.of(preferred.trim());
        }
        return firstAvailableEncoder(available);
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
}
