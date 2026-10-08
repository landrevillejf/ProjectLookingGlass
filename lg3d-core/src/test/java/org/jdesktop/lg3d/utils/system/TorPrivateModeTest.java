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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the private (Tor) mode's pure decisions: the state
 * transition table, the monitor kill-switch tick, the SOCKS enforcement
 * property/env maps, the port resolution and the fail-closed leak-check
 * parser. The live {@code enable}/{@code disable}/{@code leakCheck} paths
 * spawn processes or open sockets and are deliberately not exercised here -
 * every branch they delegate to is covered through the pure helpers.
 */
class TorPrivateModeTest {

    @AfterEach
    void clearPortOverride() {
        System.clearProperty(TorPrivateMode.SOCKS_PORT_PROPERTY);
    }

    // ------------------------------------------------------------------
    // Transition table

    @Test
    @DisplayName("enable walks OFF -> ENABLING -> ON and failures fall back to OFF")
    void enablePathTransitions() {
        assertEquals(TorPrivateMode.State.ENABLING, TorPrivateMode.transition(
                TorPrivateMode.State.OFF, TorPrivateMode.Event.ENABLE_REQUESTED));
        assertEquals(TorPrivateMode.State.ON, TorPrivateMode.transition(
                TorPrivateMode.State.ENABLING, TorPrivateMode.Event.ENABLE_SUCCEEDED));
        assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.transition(
                TorPrivateMode.State.ENABLING, TorPrivateMode.Event.ENABLE_FAILED));
    }

    @Test
    @DisplayName("the kill switch trips ON -> CUT and recovers CUT -> ON")
    void killSwitchTransitions() {
        assertEquals(TorPrivateMode.State.CUT, TorPrivateMode.transition(
                TorPrivateMode.State.ON, TorPrivateMode.Event.TOR_DOWN));
        assertEquals(TorPrivateMode.State.ON, TorPrivateMode.transition(
                TorPrivateMode.State.CUT, TorPrivateMode.Event.TOR_RESTORED));
    }

    @Test
    @DisplayName("disable returns to OFF from every state")
    void disableFromEveryState() {
        for (TorPrivateMode.State s : TorPrivateMode.State.values()) {
            assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.transition(
                    s, TorPrivateMode.Event.DISABLE_REQUESTED),
                    "disabling from " + s + " must land on OFF");
        }
    }

    @Test
    @DisplayName("inapplicable events leave the state untouched")
    void inapplicableEventsAreNoOps() {
        assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.transition(
                TorPrivateMode.State.OFF, TorPrivateMode.Event.TOR_DOWN),
                "tor dying while off changes nothing");
        assertEquals(TorPrivateMode.State.ON, TorPrivateMode.transition(
                TorPrivateMode.State.ON, TorPrivateMode.Event.ENABLE_REQUESTED),
                "re-requesting enable while on changes nothing");
        assertEquals(TorPrivateMode.State.CUT, TorPrivateMode.transition(
                TorPrivateMode.State.CUT, TorPrivateMode.Event.ENABLE_FAILED),
                "an enable failure only applies while enabling");
    }

    @Test
    @DisplayName("null inputs are tolerated: null event no-ops, null state reads OFF")
    void nullInputsTolerated() {
        assertEquals(TorPrivateMode.State.ON, TorPrivateMode.transition(
                TorPrivateMode.State.ON, null));
        assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.transition(
                null, TorPrivateMode.Event.TOR_DOWN));
    }

    // ------------------------------------------------------------------
    // Monitor tick (the kill switch decision)

    @Test
    @DisplayName("monitorTick trips on a non-running tor and recovers on running")
    void monitorTickMatrix() {
        assertEquals(TorPrivateMode.State.CUT, TorPrivateMode.monitorTick(
                TorPrivateMode.State.ON, PrivacyService.TorState.STOPPED));
        assertEquals(TorPrivateMode.State.CUT, TorPrivateMode.monitorTick(
                TorPrivateMode.State.ON, PrivacyService.TorState.UNKNOWN),
                "an unreadable tor state fails closed, exactly like stopped");
        assertEquals(TorPrivateMode.State.ON, TorPrivateMode.monitorTick(
                TorPrivateMode.State.CUT, PrivacyService.TorState.RUNNING));
        assertEquals(TorPrivateMode.State.ON, TorPrivateMode.monitorTick(
                TorPrivateMode.State.ON, PrivacyService.TorState.RUNNING));
        assertEquals(TorPrivateMode.State.CUT, TorPrivateMode.monitorTick(
                TorPrivateMode.State.CUT, PrivacyService.TorState.STOPPED),
                "still down while cut stays cut");
        assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.monitorTick(
                TorPrivateMode.State.OFF, PrivacyService.TorState.RUNNING),
                "the monitor never enables the mode by itself");
    }

    // ------------------------------------------------------------------
    // Enforcement maps

    @Test
    @DisplayName("proxyProperties forces SOCKS on loopback with loopback-only bypass")
    void proxyPropertiesContent() {
        Map<String, String> props = TorPrivateMode.proxyProperties(9050);
        assertEquals("127.0.0.1", props.get("socksProxyHost"));
        assertEquals("9050", props.get("socksProxyPort"));
        assertEquals("localhost|127.*", props.get("socksNonProxyHosts"),
                "only loopback may bypass the proxy");
        assertEquals(3, props.size());
        assertThrows(UnsupportedOperationException.class,
                () -> props.put("x", "y"), "the map is immutable");
    }

    @Test
    @DisplayName("proxyEnv carries socks5h for children when on, nothing when off")
    void proxyEnvContent() {
        Map<String, String> on = TorPrivateMode.proxyEnv(true, 9050);
        assertEquals("socks5h://127.0.0.1:9050", on.get("all_proxy"),
                "socks5h keeps remote DNS inside the tunnel");
        assertEquals(on.get("all_proxy"), on.get("http_proxy"));
        assertEquals(on.get("all_proxy"), on.get("HTTPS_PROXY"),
                "upper-case variants are set too for children that only read those");
        assertEquals(6, on.size());
        assertTrue(TorPrivateMode.proxyEnv(false, 9050).isEmpty(),
                "off means no proxy environment at all");
    }

    @Test
    @DisplayName("socksPort honours the override property and rejects invalid values")
    void socksPortResolution() {
        assertEquals(TorPrivateMode.DEFAULT_SOCKS_PORT, TorPrivateMode.socksPort(),
                "without the property the default tor SocksPort applies");
        System.setProperty(TorPrivateMode.SOCKS_PORT_PROPERTY, "9150");
        assertEquals(9150, TorPrivateMode.socksPort());
        System.setProperty(TorPrivateMode.SOCKS_PORT_PROPERTY, "not-a-port");
        assertEquals(TorPrivateMode.DEFAULT_SOCKS_PORT, TorPrivateMode.socksPort());
        System.setProperty(TorPrivateMode.SOCKS_PORT_PROPERTY, "0");
        assertEquals(TorPrivateMode.DEFAULT_SOCKS_PORT, TorPrivateMode.socksPort());
        System.setProperty(TorPrivateMode.SOCKS_PORT_PROPERTY, "70000");
        assertEquals(TorPrivateMode.DEFAULT_SOCKS_PORT, TorPrivateMode.socksPort());
    }

    // ------------------------------------------------------------------
    // Leak-check parser (fail-closed)

    @Test
    @DisplayName("parseTorCheck confirms tor, flags a leak, and degrades to unreachable")
    void parseTorCheckVerdicts() {
        assertEquals(TorPrivateMode.LeakVerdict.TOR_CONFIRMED,
                TorPrivateMode.parseTorCheck("{\"IsTor\":true,\"IP\":\"1.2.3.4\"}"));
        assertEquals(TorPrivateMode.LeakVerdict.TOR_CONFIRMED,
                TorPrivateMode.parseTorCheck("{ \"IsTor\" : true }"),
                "insignificant whitespace is ignored");
        assertEquals(TorPrivateMode.LeakVerdict.NOT_TOR,
                TorPrivateMode.parseTorCheck("{\"IsTor\":false,\"IP\":\"5.6.7.8\"}"),
                "a clearnet exit IP is a leak");
        assertEquals(TorPrivateMode.LeakVerdict.NOT_TOR,
                TorPrivateMode.parseTorCheck("<html>proxy error</html>"),
                "a reachable but unconfirming answer fails closed as a leak");
        assertEquals(TorPrivateMode.LeakVerdict.UNREACHABLE,
                TorPrivateMode.parseTorCheck(null));
        assertEquals(TorPrivateMode.LeakVerdict.UNREACHABLE,
                TorPrivateMode.parseTorCheck("   "));
    }

    // ------------------------------------------------------------------
    // Labels and live no-op safety

    @Test
    @DisplayName("every state has a human label")
    void describeNeverNull() {
        for (TorPrivateMode.State s : TorPrivateMode.State.values()) {
            assertFalse(TorPrivateMode.describe(s).isBlank());
        }
        assertNotNull(TorPrivateMode.describe(null));
    }

    @Test
    @DisplayName("the mode starts off and disable is a safe no-op headless")
    void liveStateStartsOff() {
        // No test ever enables the mode (that would spawn processes and flip
        // global JVM proxy state), so it must read OFF here; disable() must
        // remain a safe no-op.
        assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.state());
        assertFalse(TorPrivateMode.isOn());
        assertFalse(TorPrivateMode.isCut());
        TorPrivateMode.disable();
        assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.state());
    }

    @Test
    @DisplayName("listener registration tolerates null and absent removals")
    void listenerRegistrationSafe() {
        TorPrivateMode.addListener(null);
        TorPrivateMode.removeListener(null);
        TorPrivateMode.Listener listener = (from, to) -> { };
        TorPrivateMode.addListener(listener);
        TorPrivateMode.removeListener(listener);
    }
}
