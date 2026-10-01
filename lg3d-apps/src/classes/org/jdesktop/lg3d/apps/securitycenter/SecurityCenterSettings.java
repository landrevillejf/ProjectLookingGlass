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

/**
 * The Security Center's persisted preferences: what to scan, whether to walk
 * directories, whether to quarantine (move, never delete) infected files, which
 * ClamAV scanner to prefer, and whether to refresh definitions / the overview
 * automatically. A plain Jackson bean, so {@link SecurityCenterStore}
 * round-trips it as JSON; every setter normalises its input so a hand-edited
 * file can never produce an invalid {@code clamscan} line.
 *
 * <p>No scan content and no file data is stored here - only the target path,
 * the scan options and the quarantine folder.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityCenterSettings {

    private String scanPath = AntivirusBackend.DEFAULT_TARGET;
    private boolean recursive = true;
    private boolean quarantine = false;
    private String quarantineDir = "";
    private String preferredScanner = "";
    private boolean updateBeforeScan = false;
    private boolean refreshOverviewOnOpen = true;

    /** The folder or file to scan; blank resets to the user's home. */
    public String getScanPath() {
        return scanPath;
    }

    public void setScanPath(String scanPath) {
        this.scanPath = (scanPath == null || scanPath.isBlank())
                ? AntivirusBackend.DEFAULT_TARGET : scanPath.trim();
    }

    /** True to walk directories recursively ({@code clamscan --recursive}). */
    public boolean isRecursive() {
        return recursive;
    }

    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }

    /** True to move infected files into the quarantine folder. */
    public boolean isQuarantine() {
        return quarantine;
    }

    public void setQuarantine(boolean quarantine) {
        this.quarantine = quarantine;
    }

    /** The quarantine folder; blank means {@code <config>/quarantine}. */
    public String getQuarantineDir() {
        return quarantineDir;
    }

    public void setQuarantineDir(String quarantineDir) {
        this.quarantineDir = (quarantineDir == null) ? "" : quarantineDir.trim();
    }

    /** A user-chosen scanner executable; blank means auto-detect. */
    public String getPreferredScanner() {
        return preferredScanner;
    }

    public void setPreferredScanner(String preferredScanner) {
        this.preferredScanner = (preferredScanner == null) ? "" : preferredScanner.trim();
    }

    /** True to run {@code freshclam} before each scan. */
    public boolean isUpdateBeforeScan() {
        return updateBeforeScan;
    }

    public void setUpdateBeforeScan(boolean updateBeforeScan) {
        this.updateBeforeScan = updateBeforeScan;
    }

    /** True to probe the host posture when the overview is first shown. */
    public boolean isRefreshOverviewOnOpen() {
        return refreshOverviewOnOpen;
    }

    public void setRefreshOverviewOnOpen(boolean refreshOverviewOnOpen) {
        this.refreshOverviewOnOpen = refreshOverviewOnOpen;
    }
}
