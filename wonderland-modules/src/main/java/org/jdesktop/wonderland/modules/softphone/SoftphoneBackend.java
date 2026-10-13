/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the engine seam between the pure
 * control and whatever actually carries voice.
 *
 * The 2006 SoftphoneControlImpl WAS the engine — it spawned the bundled
 * binary, wired winp to watch its process, and pushed stdin lines by hand.
 * None of that belongs in a headless module (process spawning is live-OS
 * work, and this module may not touch it), so the port inverts the
 * dependency exactly like M7b's rasteriser slot: the control owns the STATE
 * (call id, mute, quality, recorder, listeners, the exit-once guard) and
 * delegates the MECHANISM here. The lg3d-apps ExternalSoftphoneBackend
 * implements it with ProcessBuilder + ProcessHandle (the winp replacement);
 * a headless JVM installs nothing and the control runs on
 * NoopSoftphoneBackend, where start() honestly fails — the same "degrade,
 * never pretend" contract as NoopPageRasterizer.
 */
package org.jdesktop.wonderland.modules.softphone;

/** The mechanism half of the softphone: spawn, speak, listen, stop. */
public interface SoftphoneBackend {

    /** Receives every typed stdout event of the engine, from whatever
     *  thread the engine reads it on. */
    interface EventHandler {
        void onEvent(SoftphoneEvent event);
    }

    /**
     * Launch the engine for one account.
     *
     * @param host   the SIP server / gateway host
     * @param user   the account name
     * @param port   the SIP port (&gt; 0)
     * @param number the phone number the desktop dials
     * @return true when the engine is up and its stdin is writable
     */
    boolean start(String host, String user, int port, String number);

    /** Write one stdin line (the newline framing is the backend's job).
     *  A line to a dead engine must report false, not throw. */
    boolean send(SoftphoneCommand command);

    /** Whether the engine process is alive right now. */
    boolean isRunning();

    /** Tell the engine to shut down gracefully, then reap it; idempotent. */
    void stop();
}
