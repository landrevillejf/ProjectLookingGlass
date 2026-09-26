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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CUPS printer discovery and management for the control center.
 *
 * <p>Mirrors the {@link NetworkStatus} seam shape: the parsing, formatting and
 * command-building are pure and unit-tested ({@link #parsePrinters},
 * {@link #parseDefault}, {@link #setDefaultCommand}, {@link #testPageCommand},
 * {@link #label}); {@link #read()}, {@link #defaultPrinter()} and the mutators
 * are thin probes over the standard CUPS command-line tools ({@code lpstat},
 * {@code lpoptions}, {@code lp}). On a host without CUPS every probe degrades
 * to an empty result / {@code false} rather than throwing, so the panel shows
 * "no printers" instead of failing.</p>
 */
public final class PrinterStatus {

    /** Probe timeout for each CUPS invocation, in seconds. */
    private static final long TIMEOUT_SECONDS = 5L;

    private PrinterStatus() {
        // no instances
    }

    /** A discovered printer queue and whether it currently accepts jobs. */
    public record Printer(String name, boolean accepting) {
    }

    /**
     * Parses {@code lpstat -a} output, one queue per line of the form
     * {@code "<name> accepting requests since ..."} or
     * {@code "<name> not accepting requests since ..."}. Blank and malformed
     * lines are skipped, so a partial or localised output still yields the
     * queues it does describe.
     */
    static List<Printer> parsePrinters(String lpstatA) {
        List<Printer> out = new ArrayList<>();
        if (lpstatA == null) {
            return out;
        }
        for (String line : lpstatA.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            String name = (space < 0) ? trimmed : trimmed.substring(0, space);
            if (name.isEmpty()) {
                continue;
            }
            boolean accepting = trimmed.contains(" accepting")
                    && !trimmed.contains("not accepting");
            out.add(new Printer(name, accepting));
        }
        return out;
    }

    /**
     * Parses {@code lpstat -d} output ({@code "system default destination: X"}),
     * returning the default queue name or {@code ""} when there is none
     * ({@code "no system default destination"}).
     */
    static String parseDefault(String lpstatD) {
        if (lpstatD == null) {
            return "";
        }
        int idx = lpstatD.indexOf("destination:");
        if (idx < 0) {
            return "";
        }
        return lpstatD.substring(idx + "destination:".length()).trim();
    }

    /** The discovered printer queues, empty when CUPS is absent or errors. */
    public static List<Printer> read() {
        String out = exec(new String[] {"lpstat", "-a"});
        return (out == null) ? new ArrayList<>() : parsePrinters(out);
    }

    /** The current default queue name, or {@code ""} when none / unavailable. */
    public static String defaultPrinter() {
        String out = exec(new String[] {"lpstat", "-d"});
        return (out == null) ? "" : parseDefault(out);
    }

    /** The command that makes {@code name} the system default printer. */
    static String[] setDefaultCommand(String name) {
        return new String[] {"lpoptions", "-d", name};
    }

    /**
     * The command that submits a one-line test page to {@code name}, piping the
     * body through {@code lp} so no temporary file is needed.
     */
    static String[] testPageCommand(String name) {
        return new String[] {"sh", "-c",
            "printf 'Project Looking Glass test page\\n' | lp -d " + shellQuote(name)};
    }

    /** Makes {@code name} the default printer; false when CUPS is unavailable. */
    public static boolean setDefault(String name) {
        return name != null && !name.isEmpty() && run(setDefaultCommand(name));
    }

    /** Submits a test page to {@code name}; false when CUPS is unavailable. */
    public static boolean printTestPage(String name) {
        return name != null && !name.isEmpty() && run(testPageCommand(name));
    }

    /** Panel row text: the queue name plus its accepting/rejecting state. */
    public static String label(Printer printer) {
        if (printer == null) {
            return "";
        }
        return printer.name() + (printer.accepting() ? "  (accepting)" : "  (rejecting)");
    }

    /** Single-quotes a value for safe interpolation into a {@code sh -c} body. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Runs a command, returning true only on a zero exit within the timeout. */
    static boolean run(String[] cmd) {
        return exec(cmd) != null;
    }

    /**
     * Runs a command and returns its stdout on a zero exit, or {@code null} on
     * a non-zero exit, timeout, or any launch error (missing binary, etc.).
     */
    static String exec(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return (p.exitValue() == 0) ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
