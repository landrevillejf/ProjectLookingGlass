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
package org.jdesktop.lg3d.apps.securitycenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The AWT-free antivirus seam. The desktop ships no virus engine of its own, so
 * - exactly like the recorder handing screen capture to {@code ffmpeg} and the
 * media writer handing burning to {@code xorriso} - this backend honestly
 * delegates scanning to an installed <strong>ClamAV</strong>. It resolves which
 * scanner to drive ({@code clamdscan} talks to the fast {@code clamd} daemon;
 * {@code clamscan} loads the database itself and always works standalone),
 * builds the precise command lines, and parses their output into a
 * {@link ScanReport} / {@link VersionInfo}.
 *
 * <p>Every method here is pure and side-effect free - no process is started - so
 * the whole command / parser table is unit-testable headless. The thin, guarded
 * {@link ProcessBuilder} launch lives in {@link SecurityCenterPanel} and is only
 * ever reached from a user action.</p>
 *
 * <p>ClamAV exit codes are {@code 0} = clean, {@code 1} = virus found,
 * {@code 2} = error. {@code clamdscan} with the daemon stopped still prints a
 * SCAN SUMMARY (full of errors, no per-file lines); {@link ScanReport} exposes
 * that as a failed run so the panel can fall back to {@code clamscan}.</p>
 */
public final class AntivirusBackend {

    /**
     * Scanners this backend knows how to drive, in preference order.
     * {@code clamdscan} leads (it reuses the running {@code clamd} daemon and so
     * avoids the ~15 s database load); {@code clamscan} is the always-available
     * standalone fallback.
     */
    public static final List<String> KNOWN_SCANNERS = List.of("clamdscan", "clamscan");

    /** The ClamAV definition updater. */
    public static final String UPDATER = "freshclam";

    /** The default scan target: the user's home folder. */
    public static final String DEFAULT_TARGET = System.getProperty("user.home", "");

    private static final String SCAN_SUMMARY_MARKER = "SCAN SUMMARY";

    private AntivirusBackend() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Scanner resolution
    // ------------------------------------------------------------------

    /**
     * The first candidate scanner {@code available} reports present, in
     * {@link #KNOWN_SCANNERS} order.
     *
     * @param available a probe (typically a PATH lookup) - may be null
     * @return the first available scanner, or empty when none is
     */
    public static Optional<String> firstAvailableScanner(Predicate<String> available) {
        if (available == null) {
            return Optional.empty();
        }
        for (String scanner : KNOWN_SCANNERS) {
            if (available.test(scanner)) {
                return Optional.of(scanner);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the scanner to use: the {@code preferred} one when it is set and
     * available, otherwise the first available known scanner.
     *
     * @param preferred a user-chosen scanner (may be null/blank for auto)
     * @param available a probe for whether an executable is on the PATH
     * @return the scanner to drive, or empty when none is available
     */
    public static Optional<String> resolveScanner(String preferred,
                                                  Predicate<String> available) {
        if (preferred != null && !preferred.isBlank()
                && available != null && available.test(preferred.trim())) {
            return Optional.of(preferred.trim());
        }
        return firstAvailableScanner(available);
    }

    // ------------------------------------------------------------------
    // Command builders
    // ------------------------------------------------------------------

    /**
     * Builds the scan command line. {@code --infected} keeps the output to just
     * the threats (the SCAN SUMMARY counters are still printed), so scanning a
     * large tree does not flood the UI with one {@code OK} line per file. When
     * {@code quarantine} is set, infected files are moved (not deleted) into
     * {@code quarantineDir} via ClamAV's {@code --move}.
     *
     * @param scanner       the executable ({@code clamscan} or {@code clamdscan})
     * @param target        the file or folder to scan
     * @param recursive     true to walk directories ({@code --recursive})
     * @param quarantine    true to move infected files aside
     * @param quarantineDir the destination folder for {@code --move}
     * @return the argument list, never null; empty when a required input is blank
     */
    public static List<String> scanCommand(String scanner, String target,
                                           boolean recursive, boolean quarantine,
                                           String quarantineDir) {
        if (scanner == null || scanner.isBlank()
                || target == null || target.isBlank()) {
            return List.of();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(scanner.trim());
        cmd.add("--infected");
        if (recursive) {
            cmd.add("--recursive");
        }
        if (quarantine && quarantineDir != null && !quarantineDir.isBlank()) {
            cmd.add("--move=" + quarantineDir.trim());
        }
        cmd.add(target.trim());
        return cmd;
    }

    /**
     * Builds the definition-update command ({@code freshclam}). Updating the
     * system database usually needs privilege; the panel surfaces any
     * authorization failure rather than silently pretending it worked.
     *
     * @return the argument list, never null
     */
    public static List<String> updateCommand() {
        return List.of(UPDATER);
    }

    /**
     * Builds the version command for a scanner ({@code <scanner> --version}).
     *
     * @param scanner the executable to query
     * @return the argument list, never null; empty when the scanner is blank
     */
    public static List<String> versionCommand(String scanner) {
        if (scanner == null || scanner.isBlank()) {
            return List.of();
        }
        return List.of(scanner.trim(), "--version");
    }

    // ------------------------------------------------------------------
    // Parsers (pure)
    // ------------------------------------------------------------------

    /**
     * Parses a scanner's combined stdout/stderr into a {@link ScanReport}. The
     * parser recognises per-file status lines ({@code path: OK},
     * {@code path: SIGNATURE FOUND}, {@code path: reason ERROR}), general
     * {@code ERROR:} lines, and the SCAN SUMMARY counters, while ignoring the
     * {@code LibClamAV Warning} banner and the {@code Loading:}/{@code Compiling:}
     * progress lines {@code clamscan} prints while it builds the database.
     *
     * @param lines    the merged process output (may be null)
     * @param exitCode the process exit code
     * @return the parsed report, never null
     */
    public static ScanReport parseScanOutput(List<String> lines, int exitCode) {
        List<Detection> detections = new ArrayList<>();
        int scannedFiles = 0;
        int scannedDirs = 0;
        int infectedFiles = 0;
        int summaryTotalErrors = 0;
        int perFileErrors = 0;
        int generalErrors = 0;
        int okLines = 0;
        long knownViruses = 0L;
        String engineVersion = "";
        String timeSummary = "";
        String errorMessage = "";
        boolean sawSummary = false;

        if (lines != null) {
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("LibClamAV")
                        || line.startsWith("Loading:")
                        || line.startsWith("Compiling:")) {
                    continue;
                }
                if (line.contains(SCAN_SUMMARY_MARKER)) {
                    sawSummary = true;
                    continue;
                }
                if (line.startsWith("Start Date:") || line.startsWith("End Date:")) {
                    continue;
                }
                if (line.startsWith("ERROR:")) {
                    generalErrors++;
                    if (errorMessage.isEmpty()) {
                        errorMessage = line;
                    }
                    continue;
                }
                String summaryValue = summaryValue(line);
                if (summaryValue != null) {
                    String key = line.substring(0, line.indexOf(':')).trim();
                    switch (key) {
                        case "Scanned files" -> scannedFiles = parseInt(summaryValue, scannedFiles);
                        case "Scanned directories" -> scannedDirs = parseInt(summaryValue, scannedDirs);
                        case "Infected files" -> infectedFiles = parseInt(summaryValue, infectedFiles);
                        case "Total errors" -> summaryTotalErrors = parseInt(summaryValue, summaryTotalErrors);
                        case "Known viruses" -> knownViruses = parseLong(summaryValue, knownViruses);
                        case "Engine version" -> engineVersion = summaryValue;
                        case "Time" -> timeSummary = summaryValue;
                        default -> {
                            // Data scanned / Data read: informational, not tracked.
                        }
                    }
                    continue;
                }
                // Per-file status line.
                if (line.endsWith(" FOUND")) {
                    String body = line.substring(0, line.length() - " FOUND".length());
                    int idx = body.lastIndexOf(": ");
                    String path = (idx >= 0) ? body.substring(0, idx) : body;
                    String threat = (idx >= 0) ? body.substring(idx + 2) : "";
                    detections.add(Detection.infected(path, threat));
                } else if (line.endsWith(" ERROR")) {
                    String body = line.substring(0, line.length() - " ERROR".length());
                    int idx = body.lastIndexOf(": ");
                    String path = (idx >= 0) ? body.substring(0, idx) : body;
                    String reason = (idx >= 0) ? body.substring(idx + 2) : "";
                    detections.add(Detection.error(path, reason));
                    perFileErrors++;
                } else if (line.endsWith(" OK")) {
                    okLines++;
                }
            }
        }

        int fileLinesSeen = okLines + perFileErrors + countInfected(detections);
        int totalErrors = Math.max(summaryTotalErrors, perFileErrors + generalErrors);
        return new ScanReport(detections, scannedFiles, scannedDirs, infectedFiles,
                totalErrors, knownViruses, engineVersion, timeSummary, sawSummary,
                fileLinesSeen, exitCode, errorMessage);
    }

    /**
     * Parses the {@code ClamAV <engine>/<database>/<date>} version line.
     *
     * @param output the raw {@code --version} output (may be null / multi-line)
     * @return the parsed version, never null
     */
    public static VersionInfo parseVersion(String output) {
        if (output == null || output.isBlank()) {
            return VersionInfo.unknown();
        }
        String first = output.stripLeading();
        int nl = first.indexOf('\n');
        if (nl >= 0) {
            first = first.substring(0, nl);
        }
        first = first.trim();
        String body = first.startsWith("ClamAV")
                ? first.substring("ClamAV".length()).trim() : first;
        String[] parts = body.split("/");
        String engine = (parts.length > 0) ? parts[0].trim() : "";
        String database = (parts.length > 1) ? parts[1].trim() : "";
        return new VersionInfo(engine, database, first);
    }

    /**
     * Summarises a {@code freshclam} run for the status line.
     *
     * @param lines    the merged process output (may be null)
     * @param exitCode the process exit code
     * @return a short human-readable outcome, never null
     */
    public static String describeUpdate(List<String> lines, int exitCode) {
        String detail = "";
        if (lines != null) {
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("ClamAV update process started")) {
                    continue;
                }
                if (line.contains("up-to-date") || line.contains("updated")
                        || line.startsWith("ERROR") || line.startsWith("WARNING")) {
                    detail = line;
                    if (line.startsWith("ERROR")) {
                        break;
                    }
                }
            }
        }
        if (exitCode == 0) {
            return detail.isBlank()
                    ? "Virus definitions are up to date."
                    : "Virus definitions updated - " + detail;
        }
        String reason = detail.isBlank() ? ("exit " + exitCode) : detail;
        return "Could not update definitions (" + reason
                + "). Updating the system database usually needs administrator rights.";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Returns the value of a recognised SCAN SUMMARY {@code Key: value} line, or
     * null when the line is not a summary key (so it can be treated as a
     * per-file status line instead).
     */
    private static String summaryValue(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = line.substring(0, colon).trim();
        switch (key) {
            case "Known viruses", "Engine version", "Scanned directories",
                 "Scanned files", "Infected files", "Total errors",
                 "Data scanned", "Data read", "Time" -> {
                return line.substring(colon + 1).trim();
            }
            default -> {
                return null;
            }
        }
    }

    private static int countInfected(List<Detection> detections) {
        int n = 0;
        for (Detection d : detections) {
            if (d.isInfected()) {
                n++;
            }
        }
        return n;
    }

    /** Parses a leading integer out of a summary value, keeping {@code fallback}. */
    private static int parseInt(String value, int fallback) {
        String digits = leadingDigits(value);
        if (digits.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Parses a leading long out of a summary value, keeping {@code fallback}. */
    private static long parseLong(String value, long fallback) {
        String digits = leadingDigits(value);
        if (digits.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The leading run of digits (ignoring thousands separators) in {@code value}. */
    private static String leadingDigits(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else if (c == ',' || c == '.') {
                // thousands separators are dropped; a decimal point ends the count
                if (c == '.' && sb.length() > 0) {
                    break;
                }
            } else if (sb.length() > 0) {
                break;
            }
        }
        return sb.toString();
    }
}
