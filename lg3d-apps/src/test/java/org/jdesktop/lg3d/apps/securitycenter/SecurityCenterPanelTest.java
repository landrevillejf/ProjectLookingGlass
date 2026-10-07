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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link SecurityCenterPanel}'s construction and its result / posture
 * rendering without ever spawning a scanner or probe: the panel is built over a
 * {@link TempDir} store, then driven through its package-private
 * {@code applyReport} / {@code applySnapshot} / {@code addRecord} hooks. Swing
 * widgets construct headless, so this runs in CI.
 */
class SecurityCenterPanelTest {

    private static ScanReport clean(int files) {
        return new ScanReport(List.of(), files, 1, 0, 0, 100L, "1.4.6",
                "1 sec", true, files, 0, "");
    }

    private static ScanReport infected() {
        return new ScanReport(
                List.of(Detection.infected("/tmp/eicar", "Win.Test.EICAR_HDB-1")),
                1, 0, 1, 0, 100L, "1.4.6", "0.1 sec", true, 1, 1, "");
    }

    private static ScanReport daemonDown() {
        return new ScanReport(List.of(), 0, 0, 0, 1, 0L, "", "", true, 0, 2,
                "ERROR: Can't connect to clamd");
    }

    @Test
    @DisplayName("a fresh panel is idle, ready and unchecked")
    void defaultsAreIdle(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        assertEquals("Ready", panel.statusText());
        assertEquals(0, panel.historySize());
        assertEquals(0, panel.findingsCount());
        assertFalse(panel.isScanning());
        assertNull(panel.snapshot(), "no posture is probed until the user asks");
        assertEquals(AntivirusBackend.DEFAULT_TARGET, panel.settings().getScanPath());
    }

    @Test
    @DisplayName("stopScan is a safe no-op when nothing is scanning")
    void stopIsSafeWhenIdle(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.stopScan();
        assertFalse(panel.isScanning());
        assertEquals(0, panel.historySize());
    }

    @Test
    @DisplayName("a clean report lists no findings, records history and says so")
    void applyCleanReport(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applyReport(clean(42), 1500, "/home/me", "clamscan");
        assertEquals(0, panel.findingsCount());
        assertEquals(1, panel.historySize());
        assertFalse(panel.isScanning());
        assertTrue(panel.statusText().contains("no threats"), panel.statusText());
        assertTrue(panel.summaryText().contains("42"), panel.summaryText());
        assertTrue(panel.history().get(0).isClean());
    }

    @Test
    @DisplayName("an infected report lists the findings and flags the threat")
    void applyInfectedReport(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applyReport(infected(), 900, "/tmp", "clamscan");
        assertEquals(1, panel.findingsCount());
        assertEquals(1, panel.historySize());
        assertTrue(panel.statusText().contains("threat"), panel.statusText());
        assertFalse(panel.history().get(0).isClean());
        assertEquals(1, panel.history().get(0).getInfectedFiles());
    }

    @Test
    @DisplayName("a report that never ran is surfaced as a failure, not clean")
    void applyFailedReport(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applyReport(daemonDown(), 10, "/home/me", "clamdscan");
        assertTrue(panel.statusText().contains("failed"), panel.statusText());
        assertTrue(panel.summaryText().contains("did not run"), panel.summaryText());
        assertTrue(panel.history().get(0).isFailed());
    }

    @Test
    @DisplayName("applyReport tolerates a null report")
    void applyNullReport(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applyReport(null, 0, "/home/me", "clamscan");
        assertEquals(0, panel.findingsCount());
        assertEquals(1, panel.historySize(), "a null report still records a (failed) scan");
        assertTrue(panel.history().get(0).isFailed());
    }

    @Test
    @DisplayName("applySnapshot renders the rating and the recommendations")
    void applySnapshot(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applySnapshot(new SecuritySnapshot(
                SecurityProbe.SelinuxMode.PERMISSIVE,
                SecurityProbe.FirewallState.NOT_RUNNING,
                true, "clamscan", new VersionInfo("1.4.6", "27171", "")));
        assertEquals("Attention needed",
                panel.snapshot().rating());
        assertTrue(panel.ratingText().contains("Attention needed"), panel.ratingText());
        assertEquals(2, panel.concernsCount(), "permissive + firewall down");
        assertEquals("Security posture updated.", panel.statusText());

        // A null snapshot is ignored (the previous one is kept).
        panel.applySnapshot(null);
        assertEquals("Attention needed", panel.snapshot().rating());
    }

    @Test
    @DisplayName("a fully protected snapshot shows the all-clear recommendation")
    void applyGoodSnapshot(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applySnapshot(new SecuritySnapshot(
                SecurityProbe.SelinuxMode.ENFORCING,
                SecurityProbe.FirewallState.RUNNING,
                true, "clamscan", new VersionInfo("1.4.6", "27171", "")));
        assertTrue(panel.ratingText().contains("Good"), panel.ratingText());
        assertEquals(1, panel.concernsCount(), "a single 'no action needed' line");
    }

    @Test
    @DisplayName("addRecord grows the history and ignores null")
    void addRecordGrowsHistory(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.addRecord(new ScanRecord("/tmp/a", "clamscan", clean(1)));
        panel.addRecord(new ScanRecord("/tmp/b", "clamscan", clean(2)));
        assertEquals(2, panel.historySize());
        assertEquals("/tmp/a", panel.history().get(0).getTarget(), "history() is oldest-first");
        panel.addRecord(null);
        assertEquals(2, panel.historySize(), "a null record is ignored");
    }

    @Test
    @DisplayName("a persisted history is reloaded on the next open")
    void reloadsPersistedHistory(@TempDir Path dir) {
        SecurityCenterPanel first = new SecurityCenterPanel(new SecurityCenterStore(dir));
        first.addRecord(new ScanRecord("/tmp/keep", "clamscan", clean(5)));

        SecurityCenterPanel second = new SecurityCenterPanel(new SecurityCenterStore(dir));
        assertEquals(1, second.historySize());
        assertEquals("/tmp/keep", second.history().get(0).getTarget());
        assertTrue(second.statusText().startsWith("Ready"), "reload does not probe");
    }

    @Test
    @DisplayName("the panel offers Antivirus / Security Overview / Privacy tabs")
    void hasThreeTabs(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        assertEquals(3, panel.tabCount());
        assertEquals("Antivirus", panel.tabTitle(0));
        assertEquals("Security Overview", panel.tabTitle(1));
        assertEquals("Privacy", panel.tabTitle(2));
    }

    @Test
    @DisplayName("renderHostServices drives the AppArmor / SSH rows without probing")
    void renderHostServices(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        assertEquals("-", panel.apparmorText(), "unprobed rows start blank");
        assertEquals("-", panel.sshText());

        panel.renderHostServices("Loaded (3 enforce, 0 complain)", "Running");
        assertTrue(panel.apparmorText().contains("Loaded"), panel.apparmorText());
        assertEquals("Running", panel.sshText());

        // Nulls render as "-", never an NPE.
        panel.renderHostServices(null, null);
        assertEquals("-", panel.apparmorText());
        assertEquals("-", panel.sshText());
    }
}
