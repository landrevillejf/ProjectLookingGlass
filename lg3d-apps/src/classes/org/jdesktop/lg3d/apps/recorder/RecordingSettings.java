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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The recorder's persisted preferences: where captures are written, the audio
 * capture format, and the screen-recording source / resolution / frame rate.
 * A plain Jackson bean, so {@link RecorderStore} round-trips it as JSON; every
 * setter clamps to a sane range so a hand-edited file can never produce an
 * invalid {@code AudioFormat} or {@code ffmpeg} line.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecordingSettings {

    /** The default capture sample rate in Hz. */
    public static final int DEFAULT_SAMPLE_RATE = RecorderBackend.DEFAULT_SAMPLE_RATE;
    /** The default screen-capture frame rate. */
    public static final int DEFAULT_FPS = RecorderBackend.DEFAULT_FPS;

    private String outputDir = "";
    private int sampleRate = DEFAULT_SAMPLE_RATE;
    private int channels = 2;
    private String screenSource = RecorderBackend.DEFAULT_SOURCE;
    private String resolution = "";
    private int fps = DEFAULT_FPS;
    private boolean captureAudioWithScreen = false;
    private String preferredRecorder = "";

    /** The folder captures are written to; blank means {@code ~/Recordings}. */
    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = (outputDir == null) ? "" : outputDir.trim();
    }

    /** The audio capture rate in Hz (clamped to 8000..192000). */
    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = Math.max(8000, Math.min(192000, sampleRate));
    }

    /** The channel count (clamped to 1..2). */
    public int getChannels() {
        return channels;
    }

    public void setChannels(int channels) {
        this.channels = Math.max(1, Math.min(2, channels));
    }

    /** The X11 grab source (e.g. {@code :0.0}); blank resets to the default. */
    public String getScreenSource() {
        return screenSource;
    }

    public void setScreenSource(String screenSource) {
        this.screenSource = (screenSource == null || screenSource.isBlank())
                ? RecorderBackend.DEFAULT_SOURCE : screenSource.trim();
    }

    /** A {@code WIDTHxHEIGHT} grab size, or blank for the whole screen. */
    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = (resolution == null) ? "" : resolution.trim();
    }

    /** The screen-capture frame rate (clamped to 1..120). */
    public int getFps() {
        return fps;
    }

    public void setFps(int fps) {
        this.fps = Math.max(1, Math.min(120, fps));
    }

    /** True to mux the default microphone into a screen recording. */
    public boolean isCaptureAudioWithScreen() {
        return captureAudioWithScreen;
    }

    public void setCaptureAudioWithScreen(boolean captureAudioWithScreen) {
        this.captureAudioWithScreen = captureAudioWithScreen;
    }

    /** A user-chosen recorder executable; blank means auto-detect. */
    public String getPreferredRecorder() {
        return preferredRecorder;
    }

    public void setPreferredRecorder(String preferredRecorder) {
        this.preferredRecorder = (preferredRecorder == null) ? "" : preferredRecorder.trim();
    }
}
