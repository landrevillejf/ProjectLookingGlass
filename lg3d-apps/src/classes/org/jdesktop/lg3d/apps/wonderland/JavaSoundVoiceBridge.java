/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7d: the live voice bridge — the JMF audio → Java Sound
 * + G.711 replacement for the wonderland softphone module.
 *
 * The 2006 voice path ran RTP through javax.media's JMF RTPSession and the
 * Sun G.711 codec; neither has a JDK 21 build. The replacement is entirely
 * first-party: Java Sound (javax.sound.sampled, in the JDK) captures and plays
 * 8 kHz mono 16-bit PCM, the in-tree G.711 codec compresses it, the in-tree
 * RtpPacket frames the RFC 3550 header, and the in-tree JitterBuffer reorders
 * the incoming stream — all of those decisions already live, headless and
 * CI-proven in wonderland-modules. This class is only the thin layer that
 * connects them to a real microphone, a real speaker and a real UDP socket.
 *
 * Why it lives HERE and not in wonderland-modules: opening audio devices and
 * binding sockets is live OS work, and the module keeps a hard headless floor.
 *
 * The split is drawn at the device. The two halves that carry NO device —
 * packing PCM samples into an RTP frame and reading one back, and the
 * little-endian byte <-> short conversion Java Sound needs — are pure, so they
 * are static here and JavaSoundVoiceBridgeTest proves them on the CI JVM. The
 * four threads that touch a line or a socket are compile-checked and verified
 * live on the desktop; like M7a's surface and M7d's NAT probe, they are not
 * claimed from a headless run.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Objects;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.jdesktop.wonderland.modules.softphone.G711;
import org.jdesktop.wonderland.modules.softphone.JitterBuffer;
import org.jdesktop.wonderland.modules.softphone.RtpPacket;

/**
 * Carries one call's voice: capture → G.711 → RTP out, RTP in → de-jitter →
 * G.711 → play. Exactly one bridge per call; {@link #start} and {@link #stop}
 * are the only lifecycle methods and both are idempotent.
 */
public final class JavaSoundVoiceBridge {

    /** Telephone sampling rate; the RFC 3551 default for G.711. */
    public static final int SAMPLE_RATE_HZ = 8000;
    /** A 20 ms frame at 8 kHz — the G.711 packetisation interval. */
    public static final int SAMPLES_PER_PACKET = 160;
    /** Timestamp increment per packet: 20 ms of an 8 kHz clock. */
    public static final int TIMESTAMP_STEP = SAMPLES_PER_PACKET;

    /** 8-bit unsigned mu-law is what goes on the wire; capture/playback use
     *  16-bit signed PCM in this linear format. */
    static final AudioFormat PCM_FORMAT =
            new AudioFormat(SAMPLE_RATE_HZ, 16, 1, true, false);

    private final JitterBuffer jitter = new JitterBuffer();

    private volatile DatagramSocket socket;
    private volatile TargetDataLine capture;
    private volatile SourceDataLine playback;
    private volatile InetAddress remoteAddress;
    private volatile int remotePort;
    private volatile long ssrc;
    private volatile int sendSequence;
    private volatile long sendTimestamp;
    private volatile boolean running;
    /** Mic gate: while set, the capture loop reads but transmits nothing. */
    private volatile boolean muted;
    /** Playback gain, 0.0 (silent) to 1.0 (as received); the volume slider. */
    private volatile double outputGain = 1.0;
    private volatile Thread captureThread;
    private volatile Thread receiveThread;
    private volatile Thread playThread;

    /**
     * Pack one PCM frame into an RTP packet. Pure — no device — so CI proves
     * the framing: mu-law payload type, no padding/extension, and the caller's
     * sequence / timestamp / sync-source fields verbatim.
     */
    public static RtpPacket encodeFrame(short[] pcm, int sequenceNumber,
                                        long timestamp, long syncSource) {
        byte[] payload = G711.encode(pcm);
        return new RtpPacket(false, false, 0, G711.MU_LAW_PAYLOAD_TYPE,
                sequenceNumber, timestamp, syncSource, payload);
    }

    /** Expand one received RTP packet's payload back to PCM. Pure. */
    public static short[] decodePayload(RtpPacket packet) {
        return G711.decode(packet.getPayload());
    }

    /** Little-endian 16-bit signed bytes to samples, as Java Sound reads them. */
    public static short[] bytesToSamples(byte[] bytes, int length) {
        int count = length / 2;
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            int lo = bytes[2 * i] & 0xff;
            int hi = bytes[2 * i + 1];          // sign-extended
            samples[i] = (short) ((hi << 8) | lo);
        }
        return samples;
    }

    /** Samples to little-endian 16-bit signed bytes, as Java Sound writes them. */
    public static byte[] samplesToBytes(short[] samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[2 * i] = (byte) (samples[i] & 0xff);
            bytes[2 * i + 1] = (byte) ((samples[i] >> 8) & 0xff);
        }
        return bytes;
    }

    /**
     * Open the microphone and speaker and the UDP path, and start the three
     * worker threads.
     *
     * @param localPort  the UDP port to bind for RTP
     * @param remote     the peer address voice is exchanged with
     * @param remotePort the peer's RTP port
     * @return true when every resource opened; on false nothing was started
     */
    public boolean start(int localPort, InetAddress remote, int remotePort) {
        if (running) {
            return true;
        }
        Objects.requireNonNull(remote, "remote");
        try {
            DatagramSocket bound = new DatagramSocket(localPort);
            TargetDataLine mic = openCapture();
            SourceDataLine speaker = openPlayback();
            if (mic == null || speaker == null) {
                closeQuietly(bound, mic, speaker);   // no audio device -> no call
                return false;
            }
            socket = bound;
            capture = mic;
            playback = speaker;
            remoteAddress = remote;
            this.remotePort = remotePort;
            ssrc = RtpPacket.randomSsrc();
            sendSequence = 0;
            sendTimestamp = 0;
            running = true;
            mic.start();
            speaker.start();
            captureThread = worker(this::captureLoop, "lg3d-voice-capture");
            receiveThread = worker(this::receiveLoop, "lg3d-voice-receive");
            playThread = worker(this::playLoop, "lg3d-voice-play");
            return true;
        } catch (SocketException e) {
            stop();
            return false;
        }
    }

    /** Tear the call down: stop the threads, close the lines and the socket. */
    public void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) {
            s.close();               // unblocks the receive thread
        }
        joinQuietly(captureThread);
        joinQuietly(receiveThread);
        joinQuietly(playThread);
        closeLine(capture);
        closeLine(playback);
        socket = null;
        capture = null;
        playback = null;
    }

    /** Whether the bridge is carrying a call right now. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Gate the microphone. A muted bridge still captures (so unmuting is
     * instant, no line re-open) but sends nothing, which is what "Mic Off"
     * means to the person on the other end: silence, not a dropped call.
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** Whether the microphone is currently gated. */
    public boolean isMuted() {
        return muted;
    }

    /**
     * Set the playback gain the play loop scales every decoded frame by.
     * Clamped to [0,1]; values outside are the slider being lied to, not a
     * request to amplify beyond the wire's own level.
     */
    public void setOutputGain(double gain) {
        this.outputGain = Math.max(0.0, Math.min(1.0, gain));
    }

    /** The current playback gain, 0.0 to 1.0. */
    public double getOutputGain() {
        return outputGain;
    }

    /** Read one 20 ms frame at a time, encode it, and send one RTP datagram. */
    private void captureLoop() {
        short[] pcm = new short[SAMPLES_PER_PACKET];
        byte[] raw = new byte[SAMPLES_PER_PACKET * 2];
        while (running) {
            int read = capture.read(raw, 0, raw.length);
            if (read <= 0) {
                continue;
            }
            short[] samples = bytesToSamples(raw, read);
            System.arraycopy(samples, 0, pcm, 0,
                    Math.min(samples.length, pcm.length));
            if (muted) {
                continue;               // Mic Off: read, but put nothing on the wire
            }
            RtpPacket packet = encodeFrame(pcm, sendSequence & 0xffff,
                    sendTimestamp & 0xffffffffL, ssrc);
            send(packet);
            sendSequence = (sendSequence + 1) & 0xffff;
            sendTimestamp = (sendTimestamp + TIMESTAMP_STEP) & 0xffffffffL;
        }
    }

    /** Receive datagrams, parse to RTP, and feed the de-jitter buffer. */
    private void receiveLoop() {
        byte[] wire = new byte[1500];
        DatagramPacket datagram = new DatagramPacket(wire, wire.length);
        while (running) {
            try {
                socket.receive(datagram);
                RtpPacket packet = RtpPacket.parse(wire, datagram.getLength());
                jitter.put(packet);
            } catch (IOException dropped) {
                // a socket close during stop() lands here; a malformed
                // datagram is a dropped packet, both non-fatal to the call
            } catch (IllegalArgumentException notRtp) {
                // parse() refuses non-version-2 / truncated datagrams
            }
        }
    }

    /** Drain the de-jitter buffer in order and write PCM to the speaker. */
    private void playLoop() {
        while (running) {
            RtpPacket packet = jitter.poll();
            if (packet == null) {
                pause(2);                    // nothing ready this tick
                continue;
            }
            short[] pcm = decodePayload(packet);
            scale(pcm, outputGain);
            playback.write(samplesToBytes(pcm), 0, pcm.length * 2);
        }
    }

    /** Multiply every sample by {@code gain}, clamping to the 16-bit range. */
    private static void scale(short[] pcm, double gain) {
        if (gain == 1.0) {
            return;
        }
        for (int i = 0; i < pcm.length; i++) {
            double v = pcm[i] * gain;
            pcm[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(v)));
        }
    }

    private void send(RtpPacket packet) {
        byte[] wire = packet.toByteArray();
        try {
            socket.send(new DatagramPacket(wire, wire.length,
                    remoteAddress, remotePort));
        } catch (IOException e) {
            // an unreachable peer mid-call is reported through the control,
            // not here; the capture loop simply advances
        }
    }

    /** Open the microphone in the shared PCM format; null when no capture
     *  device supports it, so {@link #start} can honestly report failure. */
    private static TargetDataLine openCapture() {
        try {
            TargetDataLine line = AudioSystem.getTargetDataLine(PCM_FORMAT);
            line.open(PCM_FORMAT);
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    /** Open the speaker in the shared PCM format; null when absent. */
    private static SourceDataLine openPlayback() {
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(PCM_FORMAT);
            line.open(PCM_FORMAT);
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static Thread worker(Runnable body, String name) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void closeQuietly(DatagramSocket s, TargetDataLine mic,
                                     SourceDataLine speaker) {
        if (s != null) {
            s.close();
        }
        closeLine(mic);
        closeLine(speaker);
    }

    private static void closeLine(DataLine line) {
        if (line != null) {
            line.close();
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
