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

import java.util.List;

/**
 * The parsed result of one ClamAV scan, produced by
 * {@link AntivirusBackend#parseScanOutput} from the scanner's combined
 * stdout/stderr and its exit code. It carries the per-file {@link Detection}s,
 * the SCAN SUMMARY counters and enough signal for the UI to tell the three
 * outcomes apart: threats found, a clean scan, or a scan that never really ran
 * (for example {@code clamdscan} with the {@code clamd} daemon stopped, which
 * prints a summary full of errors and no file lines).
 *
 * @param detections        infected / unreadable files (never null)
 * @param scannedFiles      files ClamAV scanned (from the summary)
 * @param scannedDirectories directories ClamAV walked (from the summary)
 * @param infectedFiles     infected-file count (from the summary)
 * @param totalErrors       error count (summary "Total errors" plus error lines)
 * @param knownViruses      signature count the engine loaded (from the summary)
 * @param engineVersion     engine version reported in the summary, else empty
 * @param timeSummary       the summary "Time" value, else empty
 * @param sawSummary        whether a SCAN SUMMARY block was present
 * @param fileLinesSeen     per-file status lines seen (OK / FOUND / ERROR)
 * @param exitCode          the scanner process exit code
 * @param errorMessage      the first general error line, else empty
 */
public record ScanReport(
        List<Detection> detections,
        int scannedFiles,
        int scannedDirectories,
        int infectedFiles,
        int totalErrors,
        long knownViruses,
        String engineVersion,
        String timeSummary,
        boolean sawSummary,
        int fileLinesSeen,
        int exitCode,
        String errorMessage) {

    /** Normalises the immutable collections and strings. */
    public ScanReport {
        detections = (detections == null) ? List.of() : List.copyOf(detections);
        scannedFiles = Math.max(0, scannedFiles);
        scannedDirectories = Math.max(0, scannedDirectories);
        infectedFiles = Math.max(0, infectedFiles);
        totalErrors = Math.max(0, totalErrors);
        knownViruses = Math.max(0L, knownViruses);
        engineVersion = (engineVersion == null) ? "" : engineVersion.trim();
        timeSummary = (timeSummary == null) ? "" : timeSummary.trim();
        fileLinesSeen = Math.max(0, fileLinesSeen);
        errorMessage = (errorMessage == null) ? "" : errorMessage.trim();
    }

    /** An empty report, used before any scan has run. */
    public static ScanReport empty() {
        return new ScanReport(List.of(), 0, 0, 0, 0, 0L, "", "", false, 0, 0, "");
    }

    /** @return true when ClamAV matched a signature in at least one file. */
    public boolean hasThreats() {
        if (infectedFiles > 0) {
            return true;
        }
        for (Detection d : detections) {
            if (d.isInfected()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when the engine actually scanned (a summary was produced and
     *         it was not an all-errors, nothing-scanned failure such as a stopped
     *         {@code clamd} daemon).
     */
    public boolean ranSuccessfully() {
        if (!sawSummary) {
            return false;
        }
        boolean nothingButErrors = totalErrors > 0 && fileLinesSeen == 0 && infectedFiles == 0;
        return !nothingButErrors;
    }

    /** @return true when the scan ran and found no threats. */
    public boolean isClean() {
        return ranSuccessfully() && !hasThreats();
    }

    /** @return the number of infected detections actually listed. */
    public int detectionCount() {
        return detections.size();
    }
}
