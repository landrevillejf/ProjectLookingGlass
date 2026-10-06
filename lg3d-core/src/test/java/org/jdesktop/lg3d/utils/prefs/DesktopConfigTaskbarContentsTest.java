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
package org.jdesktop.lg3d.utils.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.utils.prefs.DesktopConfig.Labels;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig.TaskbarItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the taskbar-contents fields on {@link DesktopConfig}: which fixed
 * taskbar pieces are shown ({@link TaskbarItem}) and the button label style
 * ({@link Labels}). Every item defaults to shown and the label style defaults
 * to icon-only (text in the tooltip). The setters only mutate in-memory state
 * (no {@code save()}), and each test restores the defaults afterwards, so the
 * shared singleton is left clean.
 */
class DesktopConfigTaskbarContentsTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("every taskbar item is shown and labels are icon-only by default")
    void defaults() {
        cfg.resetToDefaults();
        for (TaskbarItem item : TaskbarItem.values()) {
            assertTrue(cfg.isTaskbarItemShown(item),
                    item + " should be shown by default");
        }
        assertEquals(Labels.ICONS_ONLY, cfg.getTaskbarLabels());
        assertEquals(Labels.ICONS_ONLY, DesktopConfig.DEFAULT_TASKBAR_LABELS);
    }

    @Test
    @DisplayName("hiding and re-showing an item round-trips")
    void itemVisibilityRoundTrips() {
        cfg.setTaskbarItemShown(TaskbarItem.DOCUMENTS, false);
        assertFalse(cfg.isTaskbarItemShown(TaskbarItem.DOCUMENTS));
        assertTrue(cfg.isTaskbarItemShown(TaskbarItem.DOWNLOADS),
                "hiding one item leaves the others shown");
        cfg.setTaskbarItemShown(TaskbarItem.DOCUMENTS, true);
        assertTrue(cfg.isTaskbarItemShown(TaskbarItem.DOCUMENTS));
    }

    @Test
    @DisplayName("each item hides independently")
    void itemsAreIndependent() {
        cfg.setTaskbarItemShown(TaskbarItem.CLOCK, false);
        cfg.setTaskbarItemShown(TaskbarItem.NOTIFICATIONS, false);
        assertFalse(cfg.isTaskbarItemShown(TaskbarItem.CLOCK));
        assertFalse(cfg.isTaskbarItemShown(TaskbarItem.NOTIFICATIONS));
        assertTrue(cfg.isTaskbarItemShown(TaskbarItem.START));
        assertTrue(cfg.isTaskbarItemShown(TaskbarItem.EXIT));
    }

    @Test
    @DisplayName("a null item is tolerated and reports hidden-safe")
    void nullItemIsSafe() {
        cfg.setTaskbarItemShown(null, false);
        assertFalse(cfg.isTaskbarItemShown(null));
    }

    @Test
    @DisplayName("the label style round-trips and null falls back to icon-only")
    void labelsRoundTrip() {
        cfg.setTaskbarLabels(Labels.ICONS_AND_TEXT);
        assertEquals(Labels.ICONS_AND_TEXT, cfg.getTaskbarLabels());
        cfg.setTaskbarLabels(Labels.ICONS_ONLY);
        assertEquals(Labels.ICONS_ONLY, cfg.getTaskbarLabels());
        cfg.setTaskbarLabels(Labels.ICONS_AND_TEXT);
        cfg.setTaskbarLabels(null);
        assertEquals(Labels.ICONS_ONLY, cfg.getTaskbarLabels(),
                "a null label style falls back to the icon-only default");
    }

    @Test
    @DisplayName("resetToDefaults re-shows every item and restores icon-only")
    void resetRestores() {
        cfg.setTaskbarItemShown(TaskbarItem.WORKSPACES, false);
        cfg.setTaskbarLabels(Labels.ICONS_AND_TEXT);
        cfg.resetToDefaults();
        assertTrue(cfg.isTaskbarItemShown(TaskbarItem.WORKSPACES));
        assertEquals(Labels.ICONS_ONLY, cfg.getTaskbarLabels());
    }
}
