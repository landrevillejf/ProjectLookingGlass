/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the shipped no-op backend, the twin of
 * NoopPageRasterizer — a headless JVM has no softphone to launch and must
 * not pretend otherwise. start() fails honestly, so the control reports the
 * problem through its listeners instead of faking a live phone, and every
 * state decision of SoftphoneControl stays CI-provable against a recording
 * stub rather than against this class.
 */
package org.jdesktop.wonderland.modules.softphone;

/** A backend that never runs. */
public final class NoopSoftphoneBackend implements SoftphoneBackend {

    /** The single shared instance; it holds no state. */
    public static final NoopSoftphoneBackend INSTANCE = new NoopSoftphoneBackend();

    private NoopSoftphoneBackend() {
    }

    @Override
    public boolean start(String host, String user, int port, String number) {
        return false;
    }

    @Override
    public boolean send(SoftphoneCommand command) {
        return false;
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public void stop() {
        // nothing to reap
    }
}
