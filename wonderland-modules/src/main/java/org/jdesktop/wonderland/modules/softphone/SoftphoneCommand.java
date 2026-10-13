/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the typed command side of the external
 * softphone's stdin protocol.
 *
 * The 2006 SoftphoneControlImpl drove its bundled softphone binary over a
 * line protocol on the process's stdin — the tokens extracted verbatim from
 * that class's constant pool ("register", "callID", "mute"/"Mute"/"-mute"/
 * "Unmute", "quality", "recordAudio", "record"/"pause"/"resume"/"stop",
 * "linetest", "PlaceCall=conferenceId:...,callee=...", "Shutdown", and the
 * INVISIBLE/VISIBLE state words). The port keeps the SAME wire vocabulary: a
 * modern host softphone can be scripted against the identical protocol, and
 * a reviewer can diff these lines against the 2006 bytes. What changes is
 * only who writes them: the pure control serialises through here, the
 * lg3d-apps ExternalSoftphoneBackend pushes the line into ProcessHandle's
 * stdin.
 *
 * Each factory returns the immutable command; toLine() is the exact stdin
 * line, without the terminator (the backend appends the newline the protocol
 * frames on).
 */
package org.jdesktop.wonderland.modules.softphone;

/** One line of the softphone stdin vocabulary. */
public final class SoftphoneCommand {

    private final String line;

    private SoftphoneCommand(String line) {
        this.line = line;
    }

    /** Register the client's identity with the softphone. */
    public static SoftphoneCommand register(String clientName) {
        return new SoftphoneCommand("register " + clientName);
    }

    /** Bind the call identifier the phone module correlates calls with. */
    public static SoftphoneCommand callId(String callId) {
        return new SoftphoneCommand("callID=" + callId);
    }

    /** Stop transmitting; the 2006 protocol's two spellings of one state. */
    public static SoftphoneCommand mute() {
        return new SoftphoneCommand("mute");
    }

    /** Resume transmitting. */
    public static SoftphoneCommand unmute() {
        return new SoftphoneCommand("Unmute");
    }

    /** Select a capture/transmit preset by its enum name, e.g. "quality VPN". */
    public static SoftphoneCommand quality(AudioQuality quality) {
        return new SoftphoneCommand("quality " + quality.name());
    }

    /**
     * Start recording to a WAV path — the "recordAudio" verb the constant
     * pool carries, spelled as the protocol's other argument lines: verb,
     * '=', argument.
     */
    public static SoftphoneCommand recordAudio(String path) {
        return new SoftphoneCommand("recordAudio=" + path);
    }

    /**
     * The recorder's four state verbs, verbatim from the pool: "record",
     * "pause", "resume", "stop" — bare lines, NOT recordAudio= suffixes
     * (the extraction shows them as standalone tokens beside recordAudio).
     */
    public static SoftphoneCommand resumeRecording() {
        return new SoftphoneCommand("record");
    }

    public static SoftphoneCommand pauseRecording() {
        return new SoftphoneCommand("pause");
    }

    public static SoftphoneCommand stopRecording() {
        return new SoftphoneCommand("stop");
    }

    /** Ask the softphone to run its UDP loopback test. */
    public static SoftphoneCommand lineTest() {
        return new SoftphoneCommand("linetest");
    }

    /** Place an outbound call through the conference bridge, in the exact
     *  2006 argument form. */
    public static SoftphoneCommand placeCall(String conferenceId, String callee) {
        return new SoftphoneCommand("PlaceCall=conferenceId:" + conferenceId
                + ",callee=" + callee);
    }

    /** Tell the softphone to show (or hide) its own window; VISIBLE /
     *  INVISIBLE are the state words the pool kept. */
    public static SoftphoneCommand setVisible(boolean visible) {
        return new SoftphoneCommand(visible ? "visible" : "invisible");
    }

    /** The graceful goodbyes. */
    public static SoftphoneCommand shutdown() {
        return new SoftphoneCommand("Shutdown");
    }

    /**
     * A verbatim escape hatch: the 2006 control exposed
     * sendCommandToSoftphone(String), and any caller text passing through
     * here is the point of that method — trimmed, never rewritten.
     */
    public static SoftphoneCommand verbatim(String rawLine) {
        return new SoftphoneCommand(rawLine.strip());
    }

    /** The exact stdin line, minus the framing newline. */
    public String toLine() {
        return line;
    }

    @Override
    public String toString() {
        return line;
    }
}
