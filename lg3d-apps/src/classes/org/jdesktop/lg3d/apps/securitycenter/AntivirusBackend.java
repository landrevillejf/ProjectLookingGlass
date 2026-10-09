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
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        return scanCommand(scanner, target, recursive, quarantine, quarantineDir, true);
    }

    /**
     * Builds the scan command line, choosing between a quiet and a streaming
     * output mode. With {@code infectedOnly} set the {@code --infected} flag
     * keeps the output to just the threats (the SCAN SUMMARY counters are still
     * printed), so a large tree does not flood the caller with one {@code OK}
     * line per file. Clearing it makes ClamAV print a line per file, which is
     * what lets {@link SecurityCenterPanel} drive a determinate progress bar and
     * a live "current file" read-out as it streams the output; the caller
     * discards the {@code OK} lines rather than buffering them.
     *
     * @param scanner       the executable ({@code clamscan} or {@code clamdscan})
     * @param target        the file or folder to scan
     * @param recursive     true to walk directories ({@code --recursive})
     * @param quarantine    true to move infected files aside
     * @param quarantineDir the destination folder for {@code --move}
     * @param infectedOnly  true to add {@code --infected} (quiet), false to emit
     *                      a line per file (streaming progress)
     * @return the argument list, never null; empty when a required input is blank
     */
    public static List<String> scanCommand(String scanner, String target,
                                           boolean recursive, boolean quarantine,
                                           String quarantineDir, boolean infectedOnly) {
        if (scanner == null || scanner.isBlank()
                || target == null || target.isBlank()) {
            return List.of();
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(scanner.trim());
        if (infectedOnly) {
            cmd.add("--infected");
        }
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
        ScanOutputParser parser = new ScanOutputParser();
        if (lines != null) {
            for (String line : lines) {
                parser.feed(line);
            }
        }
        return parser.toReport(exitCode);
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

    // ------------------------------------------------------------------
    // Progress parsers (pure)
    // ------------------------------------------------------------------

    /** Matches an {@code a/b} count pair, each side optionally K/M/G-suffixed. */
    private static final Pattern RATIO = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*([KMG]?)\\s*/\\s*(\\d+(?:\\.\\d+)?)\\s*([KMG]?)");

    /**
     * Parses a whole-file download percentage out of a {@code freshclam} line
     * such as {@code Downloading daily.cvd [ 45%]}. Returns empty when the line
     * carries no {@code NN%} token, so the caller can leave the bar
     * indeterminate rather than inventing progress.
     *
     * @param line one freshclam output line (may be null)
     * @return the percentage clamped to 0..100, or empty when there is none
     */
    public static OptionalInt parseFreshclamProgress(String line) {
        if (line == null) {
            return OptionalInt.empty();
        }
        int pct = line.indexOf('%');
        if (pct <= 0) {
            return OptionalInt.empty();
        }
        int i = pct - 1;
        while (i >= 0 && Character.isDigit(line.charAt(i))) {
            i--;
        }
        String digits = line.substring(i + 1, pct);
        if (digits.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Math.max(0, Math.min(100, Integer.parseInt(digits))));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Parses a percentage out of a {@code clamscan} database-load line such as
     * {@code Loading: 3s, ETA: 0s [===>  ] 4.10M/8.68M sigs} (or the matching
     * {@code Compiling:} line), computed from the {@code a/b} count ratio. Only
     * the load/compile phase is recognised; anything else yields empty so the
     * caller keeps the bar indeterminate.
     *
     * @param line one clamscan output line (may be null)
     * @return the percentage clamped to 0..100, or empty when not a load line
     */
    public static OptionalInt parseLoadProgress(String line) {
        if (line == null
                || !(line.startsWith("Loading:") || line.startsWith("Compiling:"))) {
            return OptionalInt.empty();
        }
        Matcher m = RATIO.matcher(line);
        if (!m.find()) {
            return OptionalInt.empty();
        }
        double a = scaled(m.group(1), m.group(2));
        double b = scaled(m.group(3), m.group(4));
        if (b <= 0) {
            return OptionalInt.empty();
        }
        int pct = (int) Math.round(a / b * 100.0);
        return OptionalInt.of(Math.max(0, Math.min(100, pct)));
    }

    /** Applies a K/M/G scale {@code suffix} to a parsed {@code number}. */
    private static double scaled(String number, String suffix) {
        double v;
        try {
            v = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return 0d;
        }
        switch (suffix == null ? "" : suffix) {
            case "K" -> v *= 1_000d;
            case "M" -> v *= 1_000_000d;
            case "G" -> v *= 1_000_000_000d;
            default -> {
                // no scale
            }
        }
        return v;
    }

    /**
     * An incremental, one-line-at-a-time ClamAV output parser: the streaming
     * twin of {@link AntivirusBackend#parseScanOutput}. {@link SecurityCenterPanel}
     * feeds it each line as the scanner prints it, so it can drive a live
     * progress bar (files seen, threats so far, the current file) without
     * buffering the whole run, then calls {@link #toReport(int)} once the
     * process exits to get exactly the {@link ScanReport} the batch parser would
     * have produced from the same lines.
     */
    public static final class ScanOutputParser {

        private final List<Detection> detections = new ArrayList<>();
        private int scannedFiles;
        private int scannedDirs;
        private int infectedFiles;
        private int summaryTotalErrors;
        private int perFileErrors;
        private int generalErrors;
        private int okLines;
        private long knownViruses;
        private String engineVersion = "";
        private String timeSummary = "";
        private String errorMessage = "";
        private boolean sawSummary;
        private String currentFile = "";

        /**
         * Feeds one raw output line. Null / blank lines, the {@code LibClamAV}
         * banner and the {@code Loading:}/{@code Compiling:} progress lines are
         * ignored, exactly as the batch parser ignores them.
         *
         * @param raw one line of merged scanner output (may be null)
         */
        public void feed(String raw) {
            if (raw == null) {
                return;
            }
            String line = raw.trim();
            if (line.isEmpty()
                    || line.startsWith("LibClamAV")
                    || line.startsWith("Loading:")
                    || line.startsWith("Compiling:")) {
                return;
            }
            if (line.contains(SCAN_SUMMARY_MARKER)) {
                sawSummary = true;
                return;
            }
            if (line.startsWith("Start Date:") || line.startsWith("End Date:")) {
                return;
            }
            if (line.startsWith("ERROR:")) {
                generalErrors++;
                if (errorMessage.isEmpty()) {
                    errorMessage = line;
                }
                return;
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
                return;
            }
            // Per-file status line.
            if (line.endsWith(" FOUND")) {
                String body = line.substring(0, line.length() - " FOUND".length());
                int idx = body.lastIndexOf(": ");
                String path = (idx >= 0) ? body.substring(0, idx) : body;
                String threat = (idx >= 0) ? body.substring(idx + 2) : "";
                detections.add(Detection.infected(path, threat));
                currentFile = path;
            } else if (line.endsWith(" ERROR")) {
                String body = line.substring(0, line.length() - " ERROR".length());
                int idx = body.lastIndexOf(": ");
                String path = (idx >= 0) ? body.substring(0, idx) : body;
                String reason = (idx >= 0) ? body.substring(idx + 2) : "";
                detections.add(Detection.error(path, reason));
                perFileErrors++;
                currentFile = path;
            } else if (line.endsWith(" OK")) {
                okLines++;
                int idx = line.lastIndexOf(": OK");
                currentFile = (idx >= 0) ? line.substring(0, idx) : line;
            }
        }

        /**
         * Builds the report from everything fed so far.
         *
         * @param exitCode the scanner process exit code
         * @return the parsed report, never null
         */
        public ScanReport toReport(int exitCode) {
            int fileLinesSeen = okLines + perFileErrors + countInfected(detections);
            int totalErrors = Math.max(summaryTotalErrors, perFileErrors + generalErrors);
            return new ScanReport(detections, scannedFiles, scannedDirs, infectedFiles,
                    totalErrors, knownViruses, engineVersion, timeSummary, sawSummary,
                    fileLinesSeen, exitCode, errorMessage);
        }

        /** Per-file status lines seen so far (OK + FOUND + ERROR). */
        public int filesSeen() {
            return okLines + perFileErrors + countInfected(detections);
        }

        /** Infected detections seen so far - the live threat counter. */
        public int infectedSoFar() {
            return countInfected(detections);
        }

        /** The most recent file the scanner reported on ("" before the first). */
        public String currentFile() {
            return currentFile;
        }
    }
}
