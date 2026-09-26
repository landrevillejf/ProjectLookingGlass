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
package org.jdesktop.lg3d.apps.controlcenter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.swing.JComponent;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Workspaces panel.
 *
 * <p>Building {@link WorkspacesPanel} reads the workspace snapshot through the
 * {@code Desktop2D} control-center hooks, which fall back to the persisted count
 * with no windows when no 2D shell is running (as in the test JVM); switching and
 * resizing are no-ops in that state. It creates only lightweight Swing components,
 * so constructing it under {@code java.awt.headless=true} is CI-safe.</p>
 */
class WorkspacesPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        WorkspacesPanel panel = assertDoesNotThrow(WorkspacesPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        WorkspacesPanel panel = new WorkspacesPanel();
        assertEquals("Workspaces", panel.displayName());
        assertNull(panel.icon(), "the Workspaces panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        WorkspacesPanel panel = new WorkspacesPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the count index maps a workspace count to its clamped list row")
    void countIndexClamps() {
        assertEquals(0, WorkspacesPanel.countIndexOf(WorkspaceModel.MIN_COUNT));
        assertEquals(4, WorkspacesPanel.countIndexOf(5));
        assertEquals(WorkspaceModel.MAX_COUNT - WorkspaceModel.MIN_COUNT,
                WorkspacesPanel.countIndexOf(WorkspaceModel.MAX_COUNT));
        assertEquals(0, WorkspacesPanel.countIndexOf(0), "clamped low to the minimum");
        assertEquals(WorkspaceModel.MAX_COUNT - WorkspaceModel.MIN_COUNT,
                WorkspacesPanel.countIndexOf(99), "clamped high to the maximum");
    }
}
