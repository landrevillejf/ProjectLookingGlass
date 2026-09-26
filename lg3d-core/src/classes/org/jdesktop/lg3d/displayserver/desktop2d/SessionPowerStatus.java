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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Session power/lock actions for the 2D desktop's context menu.
 *
 * <p>Mirrors the {@link PrinterStatus} / {@link TimeZoneStatus} seam shape: the
 * command-building is pure and unit-tested ({@link #lockCommand},
 * {@link #suspendCommand}, {@link #rebootCommand}, {@link #poweroffCommand});
 * the {@code available()} probes are thin wrappers over
 * {@link PrinterStatus#exec}, and the actions are fire-and-forget child
 * processes launched like {@link Desktop2DAppRegistry#launchExternal} (the
 * {@code DISPLAY} inherited, the output drained, no exception propagated). On a
 * host without {@code loginctl} / {@code systemctl} / {@code xdg-screensaver}
 * every probe degrades to {@code false} so the matching menu entry simply does
 * not appear, rather than failing.</p>
 */
public final class SessionPowerStatus {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    private SessionPowerStatus() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Availability probes
    // ------------------------------------------------------------------

    /** True when {@code loginctl} (systemd-logind) is present on this host. */
    static boolean loginctlAvailable() {
        return PrinterStatus.exec(new String[] {"loginctl", "--version"}) != null;
    }

    /** True when {@code systemctl} (systemd) is present on this host. */
    static boolean systemctlAvailable() {
        return PrinterStatus.exec(new String[] {"systemctl", "--version"}) != null;
    }

    /** True when {@code xdg-screensaver} is present on this host. */
    static boolean xdgScreensaverAvailable() {
        return PrinterStatus.exec(new String[] {"xdg-screensaver", "--help"}) != null;
    }

    // ------------------------------------------------------------------
    // Pure command builders
    // ------------------------------------------------------------------

    /**
     * The command that locks the session, preferring systemd-logind and falling
     * back to {@code xdg-screensaver}: {@code loginctl lock-session} when
     * {@code loginctl} is available, else {@code xdg-screensaver lock} when that
     * is available, else {@code null} when neither is.
     */
    static String[] lockCommand(boolean loginctl, boolean xdgScreensaver) {
        if (loginctl) {
            return new String[] {"loginctl", "lock-session"};
        }
        if (xdgScreensaver) {
            return new String[] {"xdg-screensaver", "lock"};
        }
        return null;
    }

    /** The command that suspends the machine to RAM. */
    static String[] suspendCommand() {
        return new String[] {"systemctl", "suspend"};
    }

    /** The command that reboots the machine. */
    static String[] rebootCommand() {
        return new String[] {"systemctl", "reboot"};
    }

    /** The command that powers the machine off. */
    static String[] poweroffCommand() {
        return new String[] {"systemctl", "poweroff"};
    }

    // ------------------------------------------------------------------
    // Public capability queries
    // ------------------------------------------------------------------

    /** True when the session can be locked (loginctl or xdg-screensaver). */
    public static boolean canLock() {
        return lockCommand(loginctlAvailable(), xdgScreensaverAvailable()) != null;
    }

    /** True when the machine can be suspended (systemctl). */
    public static boolean canSuspend() {
        return systemctlAvailable();
    }

    /** True when the machine can be rebooted or powered off (systemctl). */
    public static boolean canPowerOff() {
        return systemctlAvailable();
    }

    // ------------------------------------------------------------------
    // Fire-and-forget actions
    // ------------------------------------------------------------------

    /** Locks the session; a no-op when no lock tool is available. */
    public static void lock() {
        fire(lockCommand(loginctlAvailable(), xdgScreensaverAvailable()));
    }

    /** Suspends the machine to RAM. */
    public static void suspend() {
        fire(suspendCommand());
    }

    /** Reboots the machine. */
    public static void reboot() {
        fire(rebootCommand());
    }

    /** Powers the machine off. */
    public static void powerOff() {
        fire(poweroffCommand());
    }

    /**
     * Starts {@code cmd} as a detached child process on the lg3d display,
     * mirroring {@link Desktop2DAppRegistry#launchExternal}: the {@code DISPLAY}
     * is inherited, the process output is drained on a daemon thread so it never
     * blocks, and a null command or a launch failure is swallowed rather than
     * propagated (a missing tool simply does nothing).
     */
    private static void fire(String[] cmd) {
        if (cmd == null || cmd.length == 0) {
            return;
        }
        String displayName = System.getProperty("lg.lgserverdisplay");
        if (displayName == null) {
            displayName = System.getenv("DISPLAY");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (displayName != null) {
                pb.environment().put("DISPLAY", displayName);
            }
            Process process = pb.start();
            drainOutput(String.join(" ", cmd), process);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not start: " + String.join(" ", cmd), e);
        }
    }

    /** Consumes (and logs) a child process's merged output so it never blocks. */
    private static void drainOutput(final String command, Process process) {
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line = reader.readLine();
                    while (line != null) {
                        logger.log(Level.INFO, "Output from {0}: {1}",
                                new Object[] { command, line });
                        line = reader.readLine();
                    }
                } catch (Exception e) {
                    logger.log(Level.FINE, "Error reading output of " + command, e);
                } finally {
                    try {
                        reader.close();
                    } catch (Exception e) {
                        // nothing useful to do
                    }
                }
            }
        }, "2D session power: " + command);
        thread.setDaemon(true);
        thread.start();
    }
}
