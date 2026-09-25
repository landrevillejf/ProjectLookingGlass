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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * Network connectivity for the 2D taskbar indicator.
 *
 * <p>The classification and formatting are pure ({@link #classify},
 * {@link #glyph}, {@link #label}) and unit-tested; {@link #read()} is the thin
 * platform probe that walks {@link NetworkInterface} for a non-loopback,
 * up interface and guesses wifi vs ethernet from the name, returning
 * {@link State#offline()} when nothing usable is found so the indicator shows a
 * "no network" glyph rather than throwing.</p>
 */
public final class NetworkStatus {

    private NetworkStatus() {
        // no instances
    }

    /** The kind of active connection. */
    public enum Kind {
        ETHERNET,
        WIFI,
        OFFLINE
    }

    /** A network reading: whether we are online and over what kind of link. */
    public record State(boolean online, Kind kind) {

        /** The offline singleton. */
        public static State offline() {
            return new State(false, Kind.OFFLINE);
        }
    }

    /**
     * Classifies a link from the two facts the probe can gather without I/O
     * beyond the interface walk: whether any non-loopback interface is up, and
     * whether that interface looks like wifi. An offline link always reports
     * {@link Kind#OFFLINE} regardless of {@code wifiPresent}.
     */
    static State classify(boolean nonLoopbackUp, boolean wifiPresent) {
        if (!nonLoopbackUp) {
            return State.offline();
        }
        return new State(true, wifiPresent ? Kind.WIFI : Kind.ETHERNET);
    }

    /** True when an interface name suggests a wireless device. */
    static boolean isWifiName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.startsWith("wl") || lower.startsWith("wlan")
                || lower.startsWith("wifi") || lower.startsWith("ra")
                || lower.startsWith("ath");
    }

    /** Reads the current link state, or {@link State#offline()} on any error. */
    public static State read() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return State.offline();
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                // Any up, non-loopback interface with addresses counts as online.
                if (ni.getInetAddresses().hasMoreElements()) {
                    return classify(true, isWifiName(ni.getName()));
                }
            }
            return State.offline();
        } catch (SocketException | RuntimeException e) {
            return State.offline();
        }
    }

    /** Compact taskbar text: {@code "Net =="} ethernet, {@code "Net ))"} wifi, {@code "Net --"} offline. */
    public static String glyph(State state) {
        if (state == null) {
            return "Net --";
        }
        return switch (state.kind()) {
            case WIFI -> "Net ))";
            case ETHERNET -> "Net ==";
            case OFFLINE -> "Net --";
        };
    }

    /** Detailed tooltip text. */
    public static String label(State state) {
        if (state == null || !state.online()) {
            return "Network: offline";
        }
        return "Network: " + (state.kind() == Kind.WIFI ? "wi-fi" : "ethernet");
    }
}
