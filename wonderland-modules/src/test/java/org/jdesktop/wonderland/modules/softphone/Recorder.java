/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (test): one SoftphoneListener that flattens every
 * callback to a tagged string, so a test asserts the exact event sequence
 * (the exit-once rule, the mute echo order) with a single list comparison.
 */
package org.jdesktop.wonderland.modules.softphone;

import java.util.ArrayList;
import java.util.List;

/** A SoftphoneListener that records every callback as a line. */
class Recorder implements SoftphoneListener {

    final List<String> events = new ArrayList<>();

    @Override
    public void softphoneVisible(boolean visible) {
        events.add("visible=" + visible);
    }

    @Override
    public void softphoneMuted(boolean muted) {
        events.add("muted=" + muted);
    }

    @Override
    public void softphoneConnected(boolean connected) {
        events.add("connected=" + connected);
    }

    @Override
    public void softphoneExited() {
        events.add("exited");
    }

    @Override
    public void softphoneProblem(String message) {
        events.add("problem=" + message);
    }

    @Override
    public void softphoneTestUDPPort(int sentPort, int receivedPort) {
        events.add("udptest=" + sentPort + '/' + receivedPort);
    }

    @Override
    public void microphoneGainTooHigh() {
        events.add("gainTooHigh");
    }
}
