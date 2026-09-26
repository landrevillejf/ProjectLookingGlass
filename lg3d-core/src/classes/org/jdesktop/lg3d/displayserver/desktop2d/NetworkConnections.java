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

import java.util.ArrayList;
import java.util.List;

/**
 * NetworkManager connection discovery and management for the control center.
 *
 * <p>Companion to {@link NetworkStatus} (which only reports the live link for
 * the taskbar glyph): this seam lists the <em>saved</em> connections and can
 * activate / deactivate them. The parsing, formatting and command-building are
 * pure and unit-tested ({@link #parseConnections}, {@link #activateCommand},
 * {@link #deactivateCommand}, {@link #label}); {@link #read()},
 * {@link #activate(String)} and {@link #deactivate(String)} are thin probes
 * over {@code nmcli}. On a host without NetworkManager every probe degrades to
 * an empty result / {@code false} rather than throwing, so the panel shows
 * "no connections" instead of failing.</p>
 */
public final class NetworkConnections {

    private NetworkConnections() {
        // no instances
    }

    /**
     * A saved NetworkManager connection. {@code device} is the interface it is
     * currently bound to, or {@code ""} when the connection is inactive; the
     * derived {@code active} flag is what the panel shows as the state.
     */
    public record Connection(String name, String type, String device, boolean active) {
    }

    /**
     * Parses {@code nmcli -t -f NAME,TYPE,DEVICE connection show} output, one
     * connection per line of colon-separated fields (the device field is empty
     * for inactive connections). Blank and malformed lines are skipped.
     */
    static List<Connection> parseConnections(String nmcliT) {
        List<Connection> out = new ArrayList<>();
        if (nmcliT == null) {
            return out;
        }
        for (String raw : nmcliT.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> f = splitFields(line);
            if (f.size() < 3) {
                continue;
            }
            String name = f.get(0);
            if (name.isEmpty()) {
                continue;
            }
            String device = f.get(2);
            out.add(new Connection(name, f.get(1), device, !device.isEmpty()));
        }
        return out;
    }

    /**
     * Splits a {@code nmcli -t} line on unescaped colons, unescaping a literal
     * colon that {@code nmcli} writes as backslash-colon inside a field so a
     * connection name containing a colon is not mistaken for a separator.
     */
    static List<String> splitFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == ':') {
                cur.append(':');
                i++;
            } else if (c == ':') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    /** The saved connections, empty when NetworkManager is absent or errors. */
    public static List<Connection> read() {
        String out = PrinterStatus.exec(new String[] {
            "nmcli", "-t", "-f", "NAME,TYPE,DEVICE", "connection", "show"});
        return (out == null) ? new ArrayList<>() : parseConnections(out);
    }

    /** True when the {@code nmcli} tool is present on this host. */
    public static boolean available() {
        return PrinterStatus.exec(new String[] {"nmcli", "--version"}) != null;
    }

    /** The command that activates (connects) the named connection. */
    static String[] activateCommand(String name) {
        return new String[] {"nmcli", "connection", "up", "id", name};
    }

    /** The command that deactivates (disconnects) the named connection. */
    static String[] deactivateCommand(String name) {
        return new String[] {"nmcli", "connection", "down", "id", name};
    }

    /** Activates the named connection; false when nmcli is unavailable. */
    public static boolean activate(String name) {
        return name != null && !name.isEmpty()
                && PrinterStatus.run(activateCommand(name));
    }

    /** Deactivates the named connection; false when nmcli is unavailable. */
    public static boolean deactivate(String name) {
        return name != null && !name.isEmpty()
                && PrinterStatus.run(deactivateCommand(name));
    }

    /** Panel row text: name, type and the active device or "inactive". */
    public static String label(Connection c) {
        if (c == null) {
            return "";
        }
        String state = c.active() ? c.device() : "inactive";
        return c.name() + "  (" + c.type() + ", " + state + ")";
    }
}
