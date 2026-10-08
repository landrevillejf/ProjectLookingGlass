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
package org.jdesktop.lg3d.apps.webbrowser;

import java.util.Locale;

/**
 * The browser's private (Tor) mode decisions, kept free of JavaFX so they are
 * unit-tested headlessly. {@link FxBrowser} is the thin wiring that consults
 * this seam at the two points the anonymity guarantee matters:
 *
 * <ul>
 *  <li><b>Kill switch.</b> While the desktop's {@code NetworkCut} flag is raised
 *      (tor stopped under private mode), {@link #blocks} refuses any
 *      <em>remote</em> navigation so WebKit never attempts a request that would
 *      either silently fail or - worse - leak in the clear. The caller renders
 *      {@link #failure}'s honest {@link LoadFailure.Reason#TOR_CUT} page
 *      instead. A local {@code file:} URL is deliberately not blocked: it cannot
 *      leak, and cutting it would only break the reader/preview paths.</li>
 *  <li><b>Private session.</b> {@link #forcePrivateSession} couples the mode to
 *      the existing "Private browsing" setting: while tor is on, cookies are
 *      never persisted regardless of that setting, so the guarantee cannot leak
 *      through the cookie jar.</li>
 * </ul>
 *
 * <p>Every method is pure (no I/O, no static state of its own): the caller
 * passes the live cut/on flags read from {@code NetworkCut} / {@code
 * TorPrivateMode}, so the decision is deterministic and testable.</p>
 */
final class TorCutGuard {

    private TorCutGuard() {
        // no instances
    }

    /**
     * True when a navigation to {@code url} must be aborted because the network
     * is cut: the cut flag is raised <em>and</em> the URL is remote (would leave
     * the machine). A null/blank or local URL is never blocked.
     */
    static boolean blocks(boolean cut, String url) {
        return cut && isRemote(url);
    }

    /**
     * True when {@code url} addresses a remote resource that could leak traffic
     * (http/https/ftp/ws/wss). Local schemes - {@code file:}, {@code about:},
     * {@code data:} - are excluded: they never touch the wire.
     */
    static boolean isRemote(String url) {
        if (url == null) {
            return false;
        }
        String l = url.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://")
                || l.startsWith("ftp://") || l.startsWith("ws://")
                || l.startsWith("wss://");
    }

    /**
     * The honest failure for a navigation refused by the kill switch, carrying
     * the {@link LoadFailure.Reason#TOR_CUT} presentation (title, explanation,
     * recovery tips). Never null.
     */
    static LoadFailure failure(String url) {
        return LoadFailure.of(LoadFailure.Reason.TOR_CUT, url,
                "Private (Tor) mode cut the network because tor stopped");
    }

    /**
     * Whether the browser should run a private (non-persisting) session: tor
     * mode on forces it, otherwise the user's standalone "Private browsing"
     * setting decides. Pure OR, so the coupling is explicit and testable.
     */
    static boolean forcePrivateSession(boolean torOn, boolean privateSetting) {
        return torOn || privateSetting;
    }
}
