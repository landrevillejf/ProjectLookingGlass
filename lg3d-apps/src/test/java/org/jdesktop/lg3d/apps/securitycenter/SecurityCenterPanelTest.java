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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.utils.system.SecurityPosture;
import org.jdesktop.lg3d.utils.system.TorPrivateMode;
import org.junit.jupiter.api.AfterEach;
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
    @DisplayName("applySnapshot renders the rating, the grade and the recommendations")
    void applySnapshot(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applySnapshot(new SecuritySnapshot(
                SecurityProbe.SelinuxMode.PERMISSIVE,
                SecurityProbe.FirewallState.NOT_RUNNING,
                true, "clamscan", new VersionInfo("1.4.6", "27171", "")));
        assertEquals("Attention needed",
                panel.snapshot().rating());
        assertTrue(panel.ratingText().contains("Attention needed"), panel.ratingText());
        // firewall down (HIGH) + SELinux permissive (MEDIUM) + tor off (LOW).
        assertEquals(3, panel.concernsCount(), panel.recommendationAt(0).toString());
        assertEquals("Security posture updated.", panel.statusText());

        // A grade is computed and published for the taskbar shield.
        assertNotNull(panel.score(), "a posture read computes a score");
        assertTrue(panel.scoreText().contains("Grade"), panel.scoreText());
        // The read is recorded in the activity trail.
        assertTrue(panel.auditSize() >= 1, "a posture read is audited");
        assertEquals(AuditEvent.CATEGORY_POSTURE,
                panel.audit().get(panel.audit().size() - 1).getCategory());

        // A null snapshot is ignored (the previous one is kept).
        panel.applySnapshot(null);
        assertEquals("Attention needed", panel.snapshot().rating());
    }

    @Test
    @DisplayName("a fully protected snapshot shows the all-clear and grades A")
    void applyGoodSnapshot(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applySnapshot(new SecuritySnapshot(
                SecurityProbe.SelinuxMode.ENFORCING,
                SecurityProbe.FirewallState.RUNNING,
                true, "clamscan", new VersionInfo("1.4.6", "27171", ""),
                TorPrivateMode.State.ON, true, true));
        assertTrue(panel.ratingText().contains("Good"), panel.ratingText());
        assertEquals(1, panel.concernsCount(), "a single 'no action needed' line");
        assertTrue(panel.recommendationAt(0).title().contains("No action needed"),
                panel.recommendationAt(0).title());
        assertEquals(SecurityScore.Grade.A, panel.score().grade());
        assertTrue(panel.scoreText().contains("Grade A (100/100)"), panel.scoreText());
        assertEquals("On - traffic forced through tor", panel.torText());
        assertEquals("Connected (kill switch armed)", panel.vpnText());
        // The grade is published through the core seam for the taskbar shield.
        assertEquals("A", SecurityPosture.grade());
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
    @DisplayName("the panel offers Antivirus / Overview / Privacy / Activity tabs")
    void hasFourTabs(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        assertEquals(4, panel.tabCount());
        assertEquals("Antivirus", panel.tabTitle(0));
        assertEquals("Security Overview", panel.tabTitle(1));
        assertEquals("Privacy", panel.tabTitle(2));
        assertEquals("Activity", panel.tabTitle(3));
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

    @Test
    @DisplayName("remediate switches to the tab a recommendation asks for")
    void remediateSwitchesTab(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        // tor off yields an OPEN_PRIVACY finding; no scanner yields OPEN_ANTIVIRUS.
        panel.applySnapshot(new SecuritySnapshot(
                SecurityProbe.SelinuxMode.ENFORCING,
                SecurityProbe.FirewallState.RUNNING,
                false, "", VersionInfo.unknown(),
                TorPrivateMode.State.OFF, false, false));

        int privacy = indexOfAction(panel, HardeningRules.Action.OPEN_PRIVACY);
        assertTrue(privacy >= 0, "a tor-off host offers the Privacy tab");
        panel.remediate(privacy);
        assertEquals(2, panel.selectedTabIndex(), "Remediate jumped to the Privacy tab");

        int antivirus = indexOfAction(panel, HardeningRules.Action.OPEN_ANTIVIRUS);
        assertTrue(antivirus >= 0, "a scanner-less host offers the Antivirus tab");
        panel.remediate(antivirus);
        assertEquals(0, panel.selectedTabIndex(), "Remediate jumped to the Antivirus tab");
    }

    @Test
    @DisplayName("remediate on an INFO finding only reports guidance; a bad index is safe")
    void remediateInfoAndOutOfRange(@TempDir Path dir) {
        SecurityCenterPanel panel = new SecurityCenterPanel(new SecurityCenterStore(dir));
        panel.applySnapshot(new SecuritySnapshot(
                SecurityProbe.SelinuxMode.DISABLED,
                SecurityProbe.FirewallState.RUNNING,
                true, "clamscan", new VersionInfo("1.4.6", "27171", ""),
                TorPrivateMode.State.ON, true, true));
        int info = indexOfAction(panel, HardeningRules.Action.INFO);
        assertTrue(info >= 0, "SELinux disabled is an INFO finding");
        int before = panel.selectedTabIndex();
        panel.remediate(info);
        assertEquals(before, panel.selectedTabIndex(), "an INFO finding does not move the tabs");
        assertFalse(panel.statusText().isBlank(), "the guidance is surfaced in the status line");

        // Out-of-range and negative indices are safe no-ops.
        panel.remediate(-1);
        panel.remediate(999);
        assertTrue(panel.statusText().contains("Select a recommendation"), panel.statusText());
    }

    @Test
    @DisplayName("the activity trail persists and reloads on the next open")
    void auditReloads(@TempDir Path dir) {
        SecurityCenterPanel first = new SecurityCenterPanel(new SecurityCenterStore(dir));
        first.applySnapshot(new SecuritySnapshot(
                SecurityProbe.SelinuxMode.ENFORCING,
                SecurityProbe.FirewallState.RUNNING,
                true, "clamscan", new VersionInfo("1.4.6", "27171", "")));
        int logged = first.auditSize();
        assertTrue(logged >= 1, "a posture read is audited");

        SecurityCenterPanel second = new SecurityCenterPanel(new SecurityCenterStore(dir));
        assertEquals(logged, second.auditSize(), "the trail is reloaded from disk");
        assertEquals(AuditEvent.CATEGORY_POSTURE,
                second.audit().get(second.audit().size() - 1).getCategory());
    }

    private static int indexOfAction(SecurityCenterPanel panel, HardeningRules.Action action) {
        for (int i = 0; i < panel.concernsCount(); i++) {
            if (panel.recommendationAt(i).action() == action) {
                return i;
            }
        }
        return -1;
    }

    @AfterEach
    void clearPosture() {
        SecurityPosture.clear();
    }
}
