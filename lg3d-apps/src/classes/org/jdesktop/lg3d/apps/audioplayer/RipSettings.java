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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.File;
import java.nio.file.Paths;

/**
 * The audio player's persisted CD-ripping preferences: the output
 * {@link CdRipBackend.Format format} (MP3 or WAV), the target sampling rate, the
 * MP3 bitrate, the destination folder, the CD device, and optional preferred
 * ripper / encoder executables (blank means auto-detect on the PATH).
 *
 * <p>A plain Jackson bean saved by {@link AudioPlayerStore}; the setters clamp
 * and normalise so a hand-edited config can never hold an out-of-range or
 * unsupported value - an unrecognised sampling rate falls back to the CD-native
 * {@link CdRipBackend#DEFAULT_SAMPLE_RATE}, an unrecognised bitrate to
 * {@link CdRipBackend#DEFAULT_BITRATE}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RipSettings {

    private CdRipBackend.Format format = CdRipBackend.Format.MP3;
    private int sampleRate = CdRipBackend.DEFAULT_SAMPLE_RATE;
    private int mp3Bitrate = CdRipBackend.DEFAULT_BITRATE;
    private String outputDir = "";
    private String device = "";
    private String preferredRipper = "";
    private String preferredEncoder = "";

    /** No-arg constructor for Jackson. */
    public RipSettings() {
    }

    /** @return the rip output format (never null). */
    public CdRipBackend.Format getFormat() {
        return format;
    }

    public void setFormat(CdRipBackend.Format format) {
        this.format = (format == null) ? CdRipBackend.Format.MP3 : format;
    }

    /** @return the target sampling rate in Hz. */
    public int getSampleRate() {
        return sampleRate;
    }

    /** Sets the sampling rate, snapping an unsupported value to the CD default. */
    public void setSampleRate(int sampleRate) {
        this.sampleRate = CdRipBackend.normaliseSampleRate(sampleRate);
    }

    /** @return the MP3 bitrate in kbps. */
    public int getMp3Bitrate() {
        return mp3Bitrate;
    }

    /** Sets the MP3 bitrate, snapping an unsupported value to the default. */
    public void setMp3Bitrate(int mp3Bitrate) {
        this.mp3Bitrate = CdRipBackend.normaliseBitrate(mp3Bitrate);
    }

    /**
     * @return the configured output folder, or the default
     *         {@code ~/.lg3d/audioplayer/music} when blank.
     */
    public String getOutputDir() {
        return (outputDir == null || outputDir.isBlank())
                ? defaultOutputDir() : outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = (outputDir == null) ? "" : outputDir.trim();
    }

    /** @return the configured CD device, or empty to auto-detect. */
    public String getDevice() {
        return (device == null) ? "" : device;
    }

    public void setDevice(String device) {
        this.device = (device == null) ? "" : device.trim();
    }

    /** @return the preferred ripper executable, or blank for auto-detect. */
    public String getPreferredRipper() {
        return preferredRipper;
    }

    public void setPreferredRipper(String preferredRipper) {
        this.preferredRipper =
                (preferredRipper == null) ? "" : preferredRipper.trim();
    }

    /** @return the preferred encoder executable, or blank for auto-detect. */
    public String getPreferredEncoder() {
        return preferredEncoder;
    }

    public void setPreferredEncoder(String preferredEncoder) {
        this.preferredEncoder =
                (preferredEncoder == null) ? "" : preferredEncoder.trim();
    }

    /**
     * The default destination for ripped tracks: {@code ~/.lg3d/audioplayer/music}
     * (or under the {@link AudioPlayerStore#DIR_PROPERTY} override when set).
     *
     * @return the absolute default output directory path
     */
    public static String defaultOutputDir() {
        return Paths.get(AudioPlayerStore.defaultConfigDir().toString(), "music")
                .toString();
    }

    /** The output folder as a {@link File}. */
    public File outputDirFile() {
        return new File(getOutputDir());
    }
}
