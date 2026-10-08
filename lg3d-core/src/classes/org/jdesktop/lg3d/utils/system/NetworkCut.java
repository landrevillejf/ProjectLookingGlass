/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.system;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * The desktop-wide <em>fails-closed</em> network-cut seam. When a privacy
 * guarantee breaks (the private Tor mode loses its daemon, a kill-switch-armed
 * VPN tunnel drops), the enforcing service calls {@link #cut()} and every
 * network client that consults {@link #isCut()} must refuse to talk to the
 * clearnet until {@link #restore()} is called. The philosophy is Whonix's: a
 * broken anonymity guarantee must never degrade to a silent, unannounced
 * direct connection.
 *
 * <p>This class is deliberately tiny and side-effect-free apart from the flag
 * and its listener fan-out: it owns no socket, no proxy and no firewall rule.
 * Enforcement lives in the clients (the web browser aborts navigations, the
 * SOCKS endpoint being dead already refuses every JVM socket attempt) and in
 * the services that flip the flag ({@link TorPrivateMode}, the VPN kill
 * switch). Listeners run on the thread that flipped the flag; UI listeners are
 * expected to hop to the EDT themselves.</p>
 */
public final class NetworkCut {

    private static final Logger logger = Logger.getLogger("lg.system");

    /** The registered cut/restore listeners, iterated safely during fan-out. */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** The current cut flag; volatile so clients see flips without locking. */
    private static volatile boolean cut;

    private NetworkCut() {
        // no instances
    }

    /** True while the desktop's network clients must refuse the clearnet. */
    public static boolean isCut() {
        return cut;
    }

    /**
     * Raises the cut flag. Idempotent: cutting an already-cut network neither
     * re-fires the listeners nor changes observable state.
     */
    public static void cut() {
        setState(true);
    }

    /**
     * Lowers the cut flag once the guarantee is back. Idempotent, like
     * {@link #cut()}.
     */
    public static void restore() {
        setState(false);
    }

    /**
     * Registers a listener invoked on every actual flag flip (never on an
     * idempotent re-set). A null listener is ignored.
     */
    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /** Removes a previously registered listener; absent listeners are ignored. */
    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** A short human label for a cut state; never null. */
    public static String describe(boolean isCut) {
        return isCut
                ? "network cut - the privacy guarantee is down"
                : "network open";
    }

    private static void setState(boolean value) {
        boolean old = cut;
        cut = value;
        if (old == value) {
            return;
        }
        logger.log(java.util.logging.Level.INFO,
                value ? "Network cut: desktop clients must refuse the clearnet"
                      : "Network restored: the privacy guarantee is back");
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // One misbehaving listener must not mask the flag flip or
                // starve the others.
                logger.log(java.util.logging.Level.FINE,
                        "A NetworkCut listener threw", e);
            }
        }
    }
}
