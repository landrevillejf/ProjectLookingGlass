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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One finished scan in the history: what was scanned, with which scanner, how
 * long it took, how many files were walked and how many threats were found.
 * Items are plain Jackson beans persisted by {@link SecurityCenterStore}, so the
 * class stays serialisable (no-arg constructor plus getters/setters, no process
 * or file handle). Nothing about the scanned files' contents is stored - only
 * the aggregate counts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScanRecord {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private String target = "";
    private String scanner = "";
    private long startedMillis = System.currentTimeMillis();
    private long durationMillis = 0L;
    private int scannedFiles = 0;
    private int infectedFiles = 0;
    private boolean clean = true;
    private boolean failed = false;
    private String engineVersion = "";

    /** No-arg constructor for Jackson. */
    public ScanRecord() {
    }

    /**
     * Convenience constructor.
     *
     * @param target  the scanned path
     * @param scanner the scanner executable used
     * @param report  the parsed scan result
     */
    public ScanRecord(String target, String scanner, ScanReport report) {
        this.target = (target == null) ? "" : target;
        this.scanner = (scanner == null) ? "" : scanner;
        if (report != null) {
            this.scannedFiles = report.scannedFiles();
            this.infectedFiles = Math.max(report.infectedFiles(), report.detectionCount());
            this.clean = report.isClean();
            this.failed = !report.ranSuccessfully();
            this.engineVersion = report.engineVersion();
        }
    }

    /** A short label of the target (its final path segment). */
    static String shorten(String path) {
        if (path == null || path.isBlank()) {
            return "(unknown)";
        }
        String s = path.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash < s.length() - 1) {
            s = s.substring(slash + 1);
        }
        return s.isBlank() ? path.trim() : s;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = (target == null) ? "" : target;
    }

    public String getScanner() {
        return scanner;
    }

    public void setScanner(String scanner) {
        this.scanner = (scanner == null) ? "" : scanner;
    }

    public long getStartedMillis() {
        return startedMillis;
    }

    public void setStartedMillis(long startedMillis) {
        this.startedMillis = startedMillis;
    }

    /** @return the approximate scan duration in milliseconds. */
    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = Math.max(0L, durationMillis);
    }

    public int getScannedFiles() {
        return scannedFiles;
    }

    public void setScannedFiles(int scannedFiles) {
        this.scannedFiles = Math.max(0, scannedFiles);
    }

    public int getInfectedFiles() {
        return infectedFiles;
    }

    public void setInfectedFiles(int infectedFiles) {
        this.infectedFiles = Math.max(0, infectedFiles);
    }

    /** True when the scan ran and found no threats. */
    public boolean isClean() {
        return clean;
    }

    public void setClean(boolean clean) {
        this.clean = clean;
    }

    /** True when the scan never really ran (daemon down, bad target). */
    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public void setEngineVersion(String engineVersion) {
        this.engineVersion = (engineVersion == null) ? "" : engineVersion;
    }

    @Override
    public String toString() {
        String when = STAMP.format(Instant.ofEpochMilli(startedMillis));
        String outcome;
        if (failed) {
            outcome = "did not run";
        } else if (infectedFiles > 0) {
            outcome = infectedFiles + (infectedFiles == 1 ? " threat" : " threats");
        } else {
            outcome = "clean";
        }
        return when + "  " + shorten(target) + "  -  " + outcome
                + " (" + scannedFiles + " files)";
    }
}
