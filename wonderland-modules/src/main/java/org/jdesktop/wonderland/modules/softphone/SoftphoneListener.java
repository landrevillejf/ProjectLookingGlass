/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the state-change listener of the 2006
 * client, ported method-for-method (javap of wonderland-client.jar keeps its
 * exact signature). The port preserves the vocabulary — including
 * softphoneExited(), which the 2006 SoftphoneControlImpl fired AT MOST ONCE
 * per process via its exitNotificationSent guard, and this port keeps the
 * flag inside SoftphoneControl.
 */
package org.jdesktop.wonderland.modules.softphone;

/** State changes of the softphone, as the 2006 UI saw them. */
public interface SoftphoneListener {

    /** The softphone window showed or hid. */
    void softphoneVisible(boolean visible);

    /** Transmission stopped or resumed. */
    void softphoneMuted(boolean muted);

    /** The line registered or dropped. */
    void softphoneConnected(boolean connected);

    /**
     * The softphone process is gone. Fired once — a second death notice of
     * the same process is the bug the 2006 exitNotificationSent flag existed
     * to prevent, and this port keeps the flag.
     */
    void softphoneExited();

    /** Anything the process said that needs a human: the 2006 client logged
     *  the text and left the UI to decide whether to show it. */
    void softphoneProblem(String message);

    /** The UDP loopback test came back with the ports it probed; matching
     *  ports is what told the 2006 user NAT was not eating RTP. */
    void softphoneTestUDPPort(int sentPort, int receivedPort);

    /** The microphone runs hot enough to clip; ask for less input gain. */
    void microphoneGainTooHigh();
}
