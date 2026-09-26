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
 * Bluetooth adapter and device management for the control center, over
 * {@code bluetoothctl} (BlueZ) and {@code rfkill}.
 *
 * <p>Mirrors the {@link PrinterStatus} seam shape: the parsing, formatting and
 * command-building are pure and unit-tested ({@link #parseControllers},
 * {@link #parseDevices}, {@link #parsePowered}, {@link #parseRfkill},
 * {@link #powerCommand}, {@link #connectCommand}, {@link #disconnectCommand},
 * {@link #label}); the probes are thin wrappers over the tools. On a host without
 * BlueZ, without an adapter, or with the radio rfkill-blocked, every probe
 * degrades to an empty result / {@code false} rather than throwing, so the panel
 * shows a "no adapter" state instead of failing.</p>
 */
public final class BluetoothStatus {

    private BluetoothStatus() {
        // no instances
    }

    /** A Bluetooth controller (adapter) reported by {@code bluetoothctl list}. */
    public record Controller(String mac, String name, boolean isDefault) {
    }

    /** A known Bluetooth device reported by {@code bluetoothctl devices}. */
    public record Device(String mac, String name) {
    }

    /** The rfkill radio state for Bluetooth. */
    public record Rfkill(boolean present, boolean softBlocked, boolean hardBlocked) {
    }

    /** True when the {@code bluetoothctl} tool is present on this host. */
    public static boolean available() {
        return PrinterStatus.exec(new String[] {"bluetoothctl", "--version"}) != null;
    }

    /**
     * Parses {@code bluetoothctl list} output, one controller per line of the form
     * {@code "Controller <mac> <name> #<n> [default]"}. The name may contain spaces;
     * the trailing {@code #n} index and {@code [default]} marker are stripped. Blank
     * and malformed lines are skipped.
     */
    static List<Controller> parseControllers(String listOutput) {
        List<Controller> out = new ArrayList<>();
        if (listOutput == null) {
            return out;
        }
        for (String raw : listOutput.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("Controller ")) {
                continue;
            }
            String rest = line.substring("Controller ".length()).trim();
            int space = rest.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String mac = rest.substring(0, space).trim();
            String tail = rest.substring(space + 1).trim();
            boolean isDefault = tail.contains("[default]");
            String name = tail.replaceAll("#\\d+", "").replace("[default]", "").trim();
            out.add(new Controller(mac, name.isEmpty() ? mac : name, isDefault));
        }
        return out;
    }

    /**
     * Parses {@code bluetoothctl devices} output, one device per line of the form
     * {@code "Device <mac> <name>"}. The name may contain spaces or be absent (then
     * the mac is used). Blank and malformed lines are skipped.
     */
    static List<Device> parseDevices(String devicesOutput) {
        List<Device> out = new ArrayList<>();
        if (devicesOutput == null) {
            return out;
        }
        for (String raw : devicesOutput.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("Device ")) {
                continue;
            }
            String rest = line.substring("Device ".length()).trim();
            int space = rest.indexOf(' ');
            String mac = (space < 0) ? rest : rest.substring(0, space).trim();
            if (mac.isEmpty()) {
                continue;
            }
            String name = (space < 0) ? "" : rest.substring(space + 1).trim();
            out.add(new Device(mac, name.isEmpty() ? mac : name));
        }
        return out;
    }

    /**
     * Parses the {@code "Powered: yes"} property from {@code bluetoothctl show}
     * output, or null when the property is absent.
     */
    static Boolean parsePowered(String showOutput) {
        if (showOutput == null) {
            return null;
        }
        for (String raw : showOutput.split("\n")) {
            String line = raw.trim();
            int idx = line.indexOf("Powered:");
            if (idx >= 0) {
                String value = line.substring(idx + "Powered:".length()).trim().toLowerCase();
                return value.startsWith("yes");
            }
        }
        return null;
    }

    /**
     * Parses {@code rfkill list bluetooth} output into the radio state. When the
     * output is empty (no Bluetooth radio known to rfkill) {@code present} is false.
     */
    static Rfkill parseRfkill(String rfkillOutput) {
        if (rfkillOutput == null || rfkillOutput.trim().isEmpty()) {
            return new Rfkill(false, false, false);
        }
        boolean soft = blockedValue(rfkillOutput, "Soft blocked:");
        boolean hard = blockedValue(rfkillOutput, "Hard blocked:");
        boolean present = rfkillOutput.contains("Soft blocked:")
                || rfkillOutput.contains("Hard blocked:");
        return new Rfkill(present, soft, hard);
    }

    /** True when the line holding {@code key} reads {@code "yes"}. */
    private static boolean blockedValue(String output, String key) {
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            int idx = line.indexOf(key);
            if (idx >= 0) {
                return line.substring(idx + key.length()).trim().toLowerCase().startsWith("yes");
            }
        }
        return false;
    }

    /** The adapters, empty when bluetoothctl is absent or there is no adapter. */
    public static List<Controller> controllers() {
        String out = PrinterStatus.exec(new String[] {"bluetoothctl", "list"});
        return (out == null) ? new ArrayList<>() : parseControllers(out);
    }

    /** The known devices, empty when bluetoothctl is absent. */
    public static List<Device> devices() {
        String out = PrinterStatus.exec(new String[] {"bluetoothctl", "devices"});
        return (out == null) ? new ArrayList<>() : parseDevices(out);
    }

    /** Whether the default adapter is powered on; false when unavailable. */
    public static boolean isPowered() {
        String out = PrinterStatus.exec(new String[] {"bluetoothctl", "show"});
        Boolean powered = (out == null) ? null : parsePowered(out);
        return powered != null && powered;
    }

    /** The rfkill radio state for Bluetooth; absent when rfkill is unavailable. */
    public static Rfkill rfkill() {
        return parseRfkill(PrinterStatus.exec(new String[] {"rfkill", "list", "bluetooth"}));
    }

    /** The command that powers the default adapter on or off. */
    static String[] powerCommand(boolean on) {
        return new String[] {"bluetoothctl", "power", on ? "on" : "off"};
    }

    /** The command that connects a device by mac address. */
    static String[] connectCommand(String mac) {
        return new String[] {"bluetoothctl", "connect", mac};
    }

    /** The command that disconnects a device by mac address. */
    static String[] disconnectCommand(String mac) {
        return new String[] {"bluetoothctl", "disconnect", mac};
    }

    /** Powers the adapter on or off; false when bluetoothctl is unavailable. */
    public static boolean setPower(boolean on) {
        return PrinterStatus.run(powerCommand(on));
    }

    /** Connects the device with {@code mac}; false when unavailable or the mac is blank. */
    public static boolean connect(String mac) {
        return mac != null && !mac.isBlank() && PrinterStatus.run(connectCommand(mac));
    }

    /** Disconnects the device with {@code mac}; false when unavailable or the mac is blank. */
    public static boolean disconnect(String mac) {
        return mac != null && !mac.isBlank() && PrinterStatus.run(disconnectCommand(mac));
    }

    /** Panel row text for a controller: name, mac and the default marker. */
    public static String label(Controller c) {
        if (c == null) {
            return "";
        }
        return c.name() + "  (" + c.mac() + ")" + (c.isDefault() ? "  [default]" : "");
    }

    /** Panel row text for a device: name and mac. */
    public static String label(Device d) {
        if (d == null) {
            return "";
        }
        return d.name() + "  (" + d.mac() + ")";
    }
}
