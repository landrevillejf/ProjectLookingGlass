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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link TorCutGuard} seam: the kill-switch navigation block,
 * the remote-vs-local URL classification, the honest {@code TOR_CUT} failure and
 * the private-session coupling. No JavaFX, no I/O - the guard takes the live
 * cut/on flags as arguments, so every branch is deterministic.
 */
class TorCutGuardTest {

    @Test
    @DisplayName("a cut blocks remote navigations only")
    void blocksRemoteWhenCut() {
        assertTrue(TorCutGuard.blocks(true, "https://example.com"));
        assertTrue(TorCutGuard.blocks(true, "http://example.com/x"));
        assertTrue(TorCutGuard.blocks(true, "ftp://host/file"));
        assertTrue(TorCutGuard.blocks(true, "wss://host/socket"));
    }

    @Test
    @DisplayName("no cut never blocks, whatever the URL")
    void noCutNeverBlocks() {
        assertFalse(TorCutGuard.blocks(false, "https://example.com"));
        assertFalse(TorCutGuard.blocks(false, "http://example.com"));
    }

    @Test
    @DisplayName("a cut leaves local and odd URLs alone (they cannot leak)")
    void cutSparesLocalUrls() {
        assertFalse(TorCutGuard.blocks(true, "file:///etc/hosts"),
                "a local file cannot leak, so it must not be cut");
        assertFalse(TorCutGuard.blocks(true, "about:blank"));
        assertFalse(TorCutGuard.blocks(true, "data:text/html,hi"));
        assertFalse(TorCutGuard.blocks(true, null));
        assertFalse(TorCutGuard.blocks(true, "   "));
    }

    @Test
    @DisplayName("isRemote recognises only wire schemes, case- and space-insensitive")
    void isRemoteClassification() {
        assertTrue(TorCutGuard.isRemote("HTTPS://Example.COM"));
        assertTrue(TorCutGuard.isRemote("  https://example.com  "));
        assertTrue(TorCutGuard.isRemote("ws://h"));
        assertFalse(TorCutGuard.isRemote("file:///x"));
        assertFalse(TorCutGuard.isRemote("about:blank"));
        assertFalse(TorCutGuard.isRemote(null));
    }

    @Test
    @DisplayName("the cut failure is an honest, non-retryable TOR_CUT page")
    void failureIsTorCut() {
        LoadFailure f = TorCutGuard.failure("https://example.com");
        assertSame(LoadFailure.Reason.TOR_CUT, f.getReason());
        assertEquals("https://example.com", f.getUrl());
        assertFalse(f.isRetryable(), "retrying while cut would just fail again");
        assertFalse(f.getTitle().isBlank());
        assertFalse(f.getExplanation().isBlank());
        assertFalse(f.getTips().isEmpty(), "the cut page must tell the user what to do");
        assertTrue(f.getDetail().toLowerCase().contains("tor"), f.getDetail());
    }

    @Test
    @DisplayName("tor mode on forces a private session; otherwise the setting decides")
    void forcePrivateSession() {
        assertTrue(TorCutGuard.forcePrivateSession(true, false), "tor on forces private");
        assertTrue(TorCutGuard.forcePrivateSession(true, true));
        assertTrue(TorCutGuard.forcePrivateSession(false, true), "the user setting still works");
        assertFalse(TorCutGuard.forcePrivateSession(false, false));
    }
}
