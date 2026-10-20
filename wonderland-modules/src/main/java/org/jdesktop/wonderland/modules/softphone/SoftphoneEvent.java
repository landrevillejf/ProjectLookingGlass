/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the typed event side of the external
 * softphone's stdout protocol, and its parser.
 *
 * The 2006 SoftphoneControlImpl read line events from the process's stdout
 * and hand-matched them with String.startsWith inside the reader loop — the
 * literals extracted verbatim from that class's constant pool ("Softphone is
 * visible", "Softphone exited with status N", the VU lines, the
 * softphoneTestUDPPort / microphoneGainTooHigh reports). This class lifts
 * that same vocabulary out of the reader loop into a sealed hierarchy so the
 * matching is pure, headless and CI-provable: parse() is the whole decision,
 * and the control only routes already-typed events to its listeners.
 *
 * Unrecognised lines are SoftphoneProblem verbatim — the 2006 client logged
 * whatever the process said; nothing is silently discarded on the error
 * path a user reports.
 */
package org.jdesktop.wonderland.modules.softphone;

/** One line of the softphone stdout vocabulary, typed. */
public sealed interface SoftphoneEvent {

    /** The softphone showed ({@code true}) or hid its own window. */
    record Visible(boolean visible) implements SoftphoneEvent {
    }

    /** The transmission state the process reports back after mute/unmute. */
    record Muted(boolean muted) implements SoftphoneEvent {
    }

    /** The SIP/line registration came up ({@code true}) or dropped. */
    record Connected(boolean connected) implements SoftphoneEvent {
    }

    /**
     * The process exited. {@code status} is the number the "Softphone exited
     * with status N" line carried; zero is the clean goodbye, anything else
     * is the crash the 2006 UI surfaced as a problem.
     */
    record Exited(int status) implements SoftphoneEvent {
    }

    /** The result of the UDP loopback test: the two ports it probed. */
    record TestUdpPorts(int sentPort, int receivedPort) implements SoftphoneEvent {
    }

    /** The microphone runs hot enough to clip — the 2006 gain warning. */
    record MicrophoneGainTooHigh() implements SoftphoneEvent {
    }

    /** A level-meter line; {@code device} is "Microphone" or "Speaker",
     *  the 2006 listener vocabulary. */
    record VuLevel(String device, String reading) implements SoftphoneEvent {
    }

    /** Anything the process said that is not protocol: surfaced verbatim,
     *  because a bogus line on the error channel is evidence, not noise. */
    record Problem(String text) implements SoftphoneEvent {
    }

    /**
     * Type one stdout line. Case-sensitive on purpose: the 2006 protocol is
     * a literal string match, and a parser more forgiving than the producer
     * hides drift instead of catching it.
     */
    static SoftphoneEvent parse(String line) {
        if (line == null) {
            return new Problem("");
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return new Problem("");
        }
        if (text.equals("Softphone is visible")) {
            return new Visible(true);
        }
        if (text.equals("Softphone is hidden") || text.equals("invisible")) {
            return new Visible(false);
        }
        // the mute echoes are the pool's exact spellings, capital M included
        if (text.equals("Softphone Muted") || text.equals("mute")) {
            return new Muted(true);
        }
        if (text.equals("Softphone Unmuted") || text.equals("Unmute")) {
            return new Muted(false);
        }
        if (text.equals("Softphone is connected")) {
            return new Connected(true);
        }
        if (text.equals("Softphone is disconnected")) {
            return new Connected(false);
        }
        if (text.equals("microphoneGainTooHigh")) {
            return new MicrophoneGainTooHigh();
        }
        if (text.startsWith("Softphone exited")) {
            // "Softphone exited with status N"; a status-less farewell is a
            // clean zero, exactly as the 2006 exit path treated it
            int[] status = trailingInts(text);
            return new Exited(status.length == 0 ? 0 : status[status.length - 1]);
        }
        if (text.startsWith("softphoneTestUDPPort")) {
            // "softphoneTestUDPPort <sent> <received>" — the two numbers the
            // line test echoed back for the NAT report; a short echo fills
            // the missing side with -1 so the UI can say "no answer"
            int[] ports = trailingInts(text);
            int sent = ports.length >= 2 ? ports[ports.length - 2] : -1;
            int received = ports.length >= 1 ? ports[ports.length - 1] : -1;
            return new TestUdpPorts(sent, received);
        }
        if (text.startsWith("Microphone level") || text.startsWith("Speaker level")) {
            int colon = text.indexOf(':');
            // the device word is the first token; the whole "Microphone level"
            // prefix is not the device name the listeners match on
            String device = text.split("\\s+")[0];
            String reading = colon < 0 ? "" : text.substring(colon + 1).strip();
            return new VuLevel(device, reading);
        }
        return new Problem(text);
    }

    /** Every trailing integer token, left to right; a non-numeric token ends
     *  the scan (the status / port words carry their numbers only at the
     *  end, so a trailing word means "no number was echoed"). */
    private static int[] trailingInts(String text) {
        String[] tokens = text.split("\\s+");
        int count = 0;
        // tokens[0] is always the routing prefix word ("Softphone exited",
        // "softphoneTestUDPPort"), never a number, so the backward scan only
        // has to reach index 1: bounding it there gives the same count and
        // lets an all-numeric tail exit the loop itself rather than always
        // pre-empting that exit with the break below.
        for (int i = tokens.length - 1; i >= 1; i--) {
            try {
                Integer.parseInt(tokens[i]);
                count++;
            } catch (NumberFormatException e) {
                break;
            }
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = Integer.parseInt(tokens[tokens.length - count + i]);
        }
        return out;
    }
}
