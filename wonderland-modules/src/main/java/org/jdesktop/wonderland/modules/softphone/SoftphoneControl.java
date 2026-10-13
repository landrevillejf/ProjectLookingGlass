/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the state half of the 2006
 * SoftphoneControlImpl, ported method-for-method against the engine seam.
 *
 * The 2006 class mixed two concerns: the softphone's STATE (call id, mute,
 * visibility, quality, recorder, the listener fan-out, the
 * exitNotificationSent once-flag) and its MECHANISM (spawning the bundled
 * binary, winp process watching, raw stdin writes). The mechanism cannot be
 * ported — the binary is gone and winp is retired (M7d's replacement
 * mapping: winp -> java.lang.ProcessHandle) — but the state is pure logic
 * and was never the interesting part of the old class; it is exactly what
 * headless tests could not reach back in 2006. This class owns it all: the
 * same public surface the phone module and the preferences UI called, the
 * same listener vocabulary, the same "fire softphoneExited AT MOST ONCE per
 * process" rule the exitNotificationSent flag encoded.
 *
 * The IOExceptions the 2006 signatures threw were stdin writes failing; the
 * port turns them into a false return plus a softphoneProblem callback, so
 * the phone module's UI code never needs a try/catch around a mute button.
 */
package org.jdesktop.wonderland.modules.softphone;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The softphone state machine over a {@link SoftphoneBackend}. */
public final class SoftphoneControl {

    private final SoftphoneBackend backend;
    private final SoftphoneBackend.EventHandler handler;

    private final List<SoftphoneListener> softphoneListeners =
            new CopyOnWriteArrayList<>();
    private final List<MicrophoneInfoListener> micListeners =
            new CopyOnWriteArrayList<>();
    private final List<SpeakerInfoListener> spkListeners =
            new CopyOnWriteArrayList<>();

    private final VuMeter micMeter = new VuMeter(VuMeter.MICROPHONE);
    private final VuMeter speakerMeter = new VuMeter(VuMeter.SPEAKER);
    private boolean micVuRunning;
    private boolean speakerVuRunning;

    private String callId;
    private AudioQuality quality = AudioQuality.VPN; // the 2006 "Normal" default
    private String recordingPath;
    private boolean recordingPaused;

    // volatile: the live backend reads stdout on its own thread and fires
    // events into handleEvent while the UI thread reads these on the panel
    private volatile boolean running;
    private volatile boolean muted;
    private volatile boolean visible;
    private volatile boolean connected;
    // the 2006 exitNotificationSent guard, kept verbatim in spirit: one death
    // notice per process, reset only by a fresh startSoftphone()
    private boolean exitNotified;

    /** @param backend the engine to drive; never null (use
     *        {@link NoopSoftphoneBackend#INSTANCE} when there is none) */
    public SoftphoneControl(SoftphoneBackend backend) {
        if (backend == null) {
            throw new NullPointerException("backend");
        }
        this.backend = backend;
        this.handler = this::handleEvent;
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /**
     * Bring the phone up for one account, the 2006 four-argument start.
     *
     * @return the fresh call id the phone module correlates this process
     *         with, or null when the engine could not be launched (the
     *         failure also arrives as a softphoneProblem callback)
     */
    public String startSoftphone(String host, String user, int port,
                                 String number) {
        if (running) {
            fireProblem("softphone already running");
            return null;
        }
        if (port <= 0) {
            // the 2006 client logged its own "Invalid port: N" here
            fireProblem("Invalid port: " + port);
            return null;
        }
        if (!backend.start(host, user, port, number)) {
            fireProblem("could not launch softphone for " + host + ':' + port);
            return null;
        }
        running = true;
        exitNotified = false;
        muted = false;
        connected = false;
        callId = UUID.randomUUID().toString();
        backend.send(SoftphoneCommand.callId(callId));
        backend.send(SoftphoneCommand.register(user));
        return callId;
    }

    /** The graceful goodbye: Shutdown line, reap, then dead-state notices. */
    public void stopSoftphone() {
        if (!running) {
            return;
        }
        running = false;
        connected = false;
        recordingPath = null;
        recordingPaused = false;
        micVuRunning = false;
        speakerVuRunning = false;
        micMeter.reset();
        speakerMeter.reset();
        backend.stop();
        fireExited();
    }

    /** The call id of the running phone, or null between start calls. */
    public String getCallID() {
        return callId;
    }

    /** Re-bind the call id (the phone module's join-call rewrites it). */
    public void setCallID(String newCallId) {
        this.callId = newCallId;
        if (running) {
            backend.send(SoftphoneCommand.callId(newCallId));
        }
    }

    /** Register under a client name; the 2006 re-registration path. */
    public boolean register(String clientName) {
        return running && backend.send(SoftphoneCommand.register(clientName));
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connected;
    }

    // ------------------------------------------------------------------
    // transmission / window state
    // ------------------------------------------------------------------

    /** Stop transmitting. The 2006 UI drove mute and Unmute as two verbs. */
    public boolean mute() {
        if (!running) {
            return false;
        }
        muted = true;
        backend.send(SoftphoneCommand.mute());
        fireMuted(true);
        return true;
    }

    public boolean unmute() {
        if (!running) {
            return false;
        }
        muted = false;
        backend.send(SoftphoneCommand.unmute());
        fireMuted(false);
        return true;
    }

    public boolean isMuted() {
        return muted;
    }

    /** Show or hide the softphone's own window. */
    public boolean setVisible(boolean nowVisible) {
        if (!running) {
            return false;
        }
        visible = nowVisible;
        return backend.send(SoftphoneCommand.setVisible(nowVisible));
    }

    public boolean isVisible() {
        return visible;
    }

    // ------------------------------------------------------------------
    // audio quality and recording
    // ------------------------------------------------------------------

    public AudioQuality getAudioQuality() {
        return quality;
    }

    /** Switch preset; an unknown/null preset is ignored exactly like the
     *  2006 client ignored a bad quality line. */
    public boolean setAudioQuality(AudioQuality newQuality) {
        if (newQuality == null || newQuality == quality) {
            return newQuality == quality;
        }
        quality = newQuality;
        return !running || backend.send(SoftphoneCommand.quality(newQuality));
    }

    /**
     * Start (or stop) recording the call to a WAV path — the 2006
     * recordAudio(String, boolean). Starting re-targets and resumes;
     * stopping closes the file.
     */
    public boolean recordAudio(String path, boolean start) {
        if (!running) {
            return false;
        }
        if (start) {
            recordingPath = path;
            recordingPaused = false;
            return backend.send(SoftphoneCommand.recordAudio(path))
                    && backend.send(SoftphoneCommand.resumeRecording());
        }
        recordingPath = null;
        recordingPaused = false;
        return backend.send(SoftphoneCommand.stopRecording());
    }

    public boolean pauseRecordingAudio() {
        if (!running || recordingPath == null || recordingPaused) {
            return false;
        }
        recordingPaused = true;
        return backend.send(SoftphoneCommand.pauseRecording());
    }

    public boolean resumeRecordingAudio() {
        if (!running || recordingPath == null || !recordingPaused) {
            return false;
        }
        recordingPaused = false;
        return backend.send(SoftphoneCommand.resumeRecording());
    }

    public boolean stopRecordingAudio() {
        if (!running || recordingPath == null) {
            return false;
        }
        recordingPath = null;
        recordingPaused = false;
        return backend.send(SoftphoneCommand.stopRecording());
    }

    /** The path the recorder is writing to, or null. */
    public String getRecordingPath() {
        return recordingPath;
    }

    // ------------------------------------------------------------------
    // raw access and the line test
    // ------------------------------------------------------------------

    /** The 2006 escape hatch: one verbatim stdin line. */
    public boolean sendCommandToSoftphone(String rawLine) {
        return running && backend.send(SoftphoneCommand.verbatim(rawLine));
    }

    /** Ask the engine to run its UDP loopback test; the answer comes back
     *  as a softphoneTestUDPPort callback. */
    public boolean runLineTest() {
        return running && backend.send(SoftphoneCommand.lineTest());
    }

    /** Surface an audio problem the UI detected locally (the 2006
     *  logAudioProblem): listeners only, no process traffic. */
    public void logAudioProblem(String message) {
        fireProblem(message);
    }

    // ------------------------------------------------------------------
    // VU meters
    // ------------------------------------------------------------------

    /**
     * Feed one captured PCM buffer to a meter; when the meter runs, its
     * listeners get the 2006 "Microphone level: N%" string.
     */
    public void feedMicrophoneSamples(short[] samples) {
        if (!micVuRunning) {
            return;
        }
        micMeter.update(samples);
        for (MicrophoneInfoListener listener : List.copyOf(micListeners)) {
            listener.vuMeterValue(micMeter.valueString());
        }
        if (micMeter.isGainTooHigh()) {
            fireGainTooHigh();
        }
    }

    /** Feed one played-back PCM buffer to the speaker meter. */
    public void feedSpeakerSamples(short[] samples) {
        if (!speakerVuRunning) {
            return;
        }
        speakerMeter.update(samples);
        for (SpeakerInfoListener listener : List.copyOf(spkListeners)) {
            listener.vuMeterValue(speakerMeter.valueString());
        }
    }

    /** Start or stop the microphone meter; stopping resets the needle. */
    public void startMicVuMeter(boolean start) {
        micVuRunning = start;
        if (!start) {
            micMeter.reset();
        }
    }

    public void startSpeakerVuMeter(boolean start) {
        speakerVuRunning = start;
        if (!start) {
            speakerMeter.reset();
        }
    }

    public boolean isMicVuMeterRunning() {
        return micVuRunning;
    }

    public boolean isSpeakerVuMeterRunning() {
        return speakerVuRunning;
    }

    // ------------------------------------------------------------------
    // listeners (the 2006 add/remove pairs)
    // ------------------------------------------------------------------

    public void addSoftphoneListener(SoftphoneListener listener) {
        softphoneListeners.add(listener);
    }

    public void removeSoftphoneListener(SoftphoneListener listener) {
        softphoneListeners.remove(listener);
    }

    public void addMicrophoneInfoListener(MicrophoneInfoListener listener) {
        micListeners.add(listener);
    }

    public void removeMicrophoneInfoListener(MicrophoneInfoListener listener) {
        micListeners.remove(listener);
    }

    public void addSpeakerInfoListener(SpeakerInfoListener listener) {
        spkListeners.add(listener);
    }

    public void removeSpeakerInfoListener(SpeakerInfoListener listener) {
        spkListeners.remove(listener);
    }

    // ------------------------------------------------------------------
    // the engine's event stream
    // ------------------------------------------------------------------

    /** Route one typed backend event; the backend's EventHandler target.
     *  Public because the live backend calls it from its reader thread. */
    public void handleEvent(SoftphoneEvent event) {
        switch (event) {
            case SoftphoneEvent.Visible e -> {
                visible = e.visible();
                fireVisible(e.visible());
            }
            case SoftphoneEvent.Muted e -> {
                muted = e.muted();
                fireMuted(e.muted());
            }
            case SoftphoneEvent.Connected e -> {
                connected = e.connected();
                fireConnected(e.connected());
            }
            case SoftphoneEvent.Exited e -> {
                running = false;
                connected = false;
                if (e.status() != 0) {
                    fireProblem("softphone exited with status " + e.status());
                }
                fireExited();   // once-guard inside
            }
            case SoftphoneEvent.TestUdpPorts e -> {
                for (SoftphoneListener listener
                        : List.copyOf(softphoneListeners)) {
                    listener.softphoneTestUDPPort(e.sentPort(), e.receivedPort());
                }
            }
            case SoftphoneEvent.MicrophoneGainTooHigh e -> fireGainTooHigh();
            case SoftphoneEvent.VuLevel e -> {
                // a meter line the ENGINE produced (the 2006 binary painted
                // its own needles); route by device name, same strings
                if (VuMeter.MICROPHONE.equals(e.device())) {
                    for (MicrophoneInfoListener listener
                            : List.copyOf(micListeners)) {
                        listener.vuMeterValue(e.device() + " level: " + e.reading());
                    }
                } else {
                    for (SpeakerInfoListener listener
                            : List.copyOf(spkListeners)) {
                        listener.vuMeterValue(e.device() + " level: " + e.reading());
                    }
                }
            }
            case SoftphoneEvent.Problem e -> {
                if (!e.text().isEmpty()) {
                    fireProblem(e.text());
                }
            }
        }
    }

    /** The event handler the backend installs at construction. */
    public SoftphoneBackend.EventHandler eventHandler() {
        return handler;
    }

    // ------------------------------------------------------------------
    // fan-out helpers: a listener that throws must not mute the rest of
    // the room — the 2006 UI learned that with a callback loop that called
    // each listener inside its own try/catch. This port keeps one guard at
    // the fan-out site instead of scattering thirty.
    // ------------------------------------------------------------------

    private void fireVisible(boolean nowVisible) {
        for (SoftphoneListener l : List.copyOf(softphoneListeners)) {
            try {
                l.softphoneVisible(nowVisible);
            } catch (RuntimeException ignored) {
                // the next listener still hears about it
            }
        }
    }

    private void fireMuted(boolean nowMuted) {
        for (SoftphoneListener l : List.copyOf(softphoneListeners)) {
            try {
                l.softphoneMuted(nowMuted);
            } catch (RuntimeException ignored) {
                // ditto
            }
        }
    }

    private void fireConnected(boolean nowConnected) {
        for (SoftphoneListener l : List.copyOf(softphoneListeners)) {
            try {
                l.softphoneConnected(nowConnected);
            } catch (RuntimeException ignored) {
                // ditto
            }
        }
    }

    private void fireExited() {
        if (exitNotified) {
            return;                     // the exitNotificationSent rule
        }
        exitNotified = true;
        for (SoftphoneListener l : List.copyOf(softphoneListeners)) {
            try {
                l.softphoneExited();
            } catch (RuntimeException ignored) {
                // ditto
            }
        }
    }

    private void fireProblem(String message) {
        for (SoftphoneListener l : List.copyOf(softphoneListeners)) {
            try {
                l.softphoneProblem(message);
            } catch (RuntimeException ignored) {
                // ditto
            }
        }
    }

    private void fireGainTooHigh() {
        for (SoftphoneListener l : List.copyOf(softphoneListeners)) {
            try {
                l.microphoneGainTooHigh();
            } catch (RuntimeException ignored) {
                // ditto
            }
        }
    }
}
