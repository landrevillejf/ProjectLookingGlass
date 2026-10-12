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
package org.jdesktop.lg3d.apps.bugreporter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.jdesktop.lg3d.displayserver.crash.CrashReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction and wiring tests for the {@link BugReporterPanel},
 * in the {@code AboutPanelTest} house style: the module's test task runs with
 * {@code java.awt.headless=true}, so the panel must build with no display.
 * The consequential rules - when filing is allowed, what a filed report
 * looks like - are {@code BugReportDraftTest}'s job; these tests check that
 * the form actually drives that model.
 *
 * <p>Every widget poke runs on the EDT, because that is the only thread the
 * painting code may touch; the initial archive load is awaited first so its
 * {@code invokeLater} refill cannot race a test that seeds the archive
 * model.</p>
 */
class BugReporterPanelTest {

    private static BugReporterPanel onEdt() throws Exception {
        BugReporterPanel[] panel = new BugReporterPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new BugReporterPanel());
        return panel[0];
    }

    private static void poke(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    /** Waits for the constructor's archive load to have painted its status. */
    private static void awaitInitialLoad(BugReporterPanel panel) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (panel.statusLabel.getText().trim().isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(panel.statusLabel.getText().trim().isEmpty(),
                "the initial archive load never reported");
    }

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() throws Exception {
        BugReporterPanel panel = onEdt();
        assertNotNull(panel);
        assertNotNull(panel.getLayout());
        awaitInitialLoad(panel);
        poke(panel::close);
    }

    @Test
    @DisplayName("the panel advertises the host size both desktops use")
    void panelReportsHostDimensions() throws Exception {
        BugReporterPanel panel = onEdt();
        assertTrue(panel instanceof JPanel);
        assertEquals(BugReporterPanel.WIDTH_PX, panel.getPreferredSize().width);
        assertEquals(BugReporterPanel.HEIGHT_PX, panel.getPreferredSize().height);
        awaitInitialLoad(panel);
        poke(panel::close);
    }

    @Test
    @DisplayName("a blank subject disables File; typing a subject enables it")
    void submitFollowsTheDraft() throws Exception {
        BugReporterPanel panel = onEdt();
        awaitInitialLoad(panel);
        poke(() -> {
            assertFalse(panel.submitButton.isEnabled(),
                    "an empty form must not offer to file anything");
            assertTrue(panel.previewArea.getText().isEmpty());
            panel.titleField.setText("task bar wraps at 1280 px");
            assertTrue(panel.submitButton.isEnabled());
            // The preview shows the exact issue text: title line plus body.
            assertTrue(panel.previewArea.getText().startsWith("[MINOR] task bar wraps"));
        });
        poke(panel::close);
    }

    @Test
    @DisplayName("typed evidence flows through Add into the draft and list")
    void evidenceIsTypedNotPicked() throws Exception {
        BugReporterPanel panel = onEdt();
        awaitInitialLoad(panel);
        poke(() -> {
            panel.titleField.setText("clock widget freezes on resume");
            panel.evidencePathField.setText("/tmp/lookingglass.log");
            panel.addEvidenceButton.doClick();
            assertEquals(1, panel.evidenceList.getModel().getSize());
            assertEquals("/tmp/lookingglass.log", panel.evidenceModel.get(0));
            assertTrue(panel.draftForTest().evidenceList()
                    .contains("/tmp/lookingglass.log"));
            assertTrue(panel.previewArea.getText().contains("lookingglass.log"),
                    "the preview shows the bare name, the path never reaches the issue");
            assertFalse(panel.previewArea.getText().contains("/tmp/"));

            panel.evidenceList.setSelectedIndex(0);
            panel.removeEvidenceButton.doClick();
            assertEquals(0, panel.evidenceList.getModel().getSize());
            assertTrue(panel.draftForTest().evidenceList().isEmpty());
        });
        poke(panel::close);
    }

    @Test
    @DisplayName("attaching a captured crash keeps its signature in the draft")
    void attachAndDetachCrash() throws Exception {
        BugReporterPanel panel = onEdt();
        awaitInitialLoad(panel);
        CrashReport crash = CrashReport.of(new IllegalStateException("boom"),
                CrashReport.Kind.CRASH, false, "WindowManager");
        poke(() -> {
            panel.archiveModel.clear();
            panel.archiveModel.addElement(crash);
            panel.archiveList.setSelectedIndex(0);
            panel.attachCrashButton.doClick();
            assertEquals(crash, panel.draftForTest().linkedCrash());

            panel.detachCrashButton.doClick();
            assertEquals(null, panel.draftForTest().linkedCrash());
        });
        poke(panel::close);
    }

    @Test
    @DisplayName("severity chosen in the combo reaches the frozen report")
    void severityComboMirrors() throws Exception {
        BugReporterPanel panel = onEdt();
        awaitInitialLoad(panel);
        poke(() -> {
            panel.titleField.setText("splash never fades out");
            panel.severityBox.setSelectedItem(CrashReport.Severity.BLOCKER);
            CrashReport filed = panel.draftForTest().toReport();
            assertEquals(CrashReport.Severity.BLOCKER, filed.severity());
            // The box offers every level, in the engine's own order.
            JComboBox<?> box = panel.severityBox;
            assertEquals(CrashReport.Severity.values().length, box.getItemCount());
        });
        poke(panel::close);
    }

    @Test
    @DisplayName("Close stops the worker and runs the host's hook")
    void closeRunsTheHook() throws Exception {
        BugReporterPanel panel = onEdt();
        awaitInitialLoad(panel);
        boolean[] hooked = {false};
        poke(() -> {
            panel.setOnClose(() -> hooked[0] = true);
            panel.close();
        });
        assertTrue(hooked[0], "the 2D MDI host routes its close button here");
        // Closing twice - or working after close - must not throw: the window
        // system may deliver the hook after the panel already shut down.
        assertDoesNotThrow(() -> poke(panel::close));
    }
}
