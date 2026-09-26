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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import org.jdesktop.lg3d.displayserver.desktop2d.ShortcutMap;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Shortcuts panel.
 *
 * <p>Building {@link ShortcutsPanel} reads the effective bindings through
 * {@code ShortcutMap.mergeBindings} over the persisted {@code shortcuts.custom}
 * overrides, which is pure apart from reading {@link DesktopConfig}; applying a
 * binding calls {@code Desktop2D.applyShortcuts()}, a no-op when no 2D shell is
 * running (as in the test JVM). The panel resets the config in-memory after each
 * test (never {@code save()}), so it cannot disturb the host's real preferences.
 * It creates only lightweight Swing components, so constructing it under
 * {@code java.awt.headless=true} is CI-safe.</p>
 */
class ShortcutsPanelTest {

    @AfterEach
    void resetConfig() {
        DesktopConfig.get().resetToDefaults();
    }

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        ShortcutsPanel panel = assertDoesNotThrow(ShortcutsPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        ShortcutsPanel panel = new ShortcutsPanel();
        assertEquals("Shortcuts", panel.displayName());
        assertNull(panel.icon(), "the Shortcuts panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        ShortcutsPanel panel = new ShortcutsPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the rebindable action ids cover the named actions and every workspace move")
    void actionIdsCoverDefaultsAndWorkspaceMoves() {
        List<String> ids = ShortcutsPanel.actionIds();
        assertEquals(9 + WorkspaceModel.MAX_COUNT, ids.size(),
                "nine named actions plus one move-to-workspace action per workspace");
        assertTrue(ids.contains(ShortcutMap.SHOW_DESKTOP));
        assertTrue(ids.contains(ShortcutMap.RUN_DIALOG));
        assertTrue(ids.contains(ShortcutMap.MOVE_TO_WORKSPACE_PREFIX + "0"),
                "the first per-workspace move action is rebindable");
    }

    @Test
    @DisplayName("the effective bindings invert the merged table to action -> keystroke spec")
    void effectiveBindingsInvertMergedTable() {
        Map<String, String> bindings = ShortcutsPanel.effectiveBindings();
        assertNotNull(bindings);
        assertFalse(bindings.isEmpty(), "the built-in defaults are always present");
        assertTrue(bindings.containsKey(ShortcutMap.SHOW_DESKTOP));
        assertEquals("control alt D", bindings.get(ShortcutMap.SHOW_DESKTOP),
                "the default show-desktop binding survives with no custom overrides");
    }

    @Test
    @DisplayName("a key spec is valid only when ShortcutMap can parse it")
    void keySpecValidation() {
        assertTrue(ShortcutsPanel.isValidKeySpec("control alt T"));
        assertTrue(ShortcutsPanel.isValidKeySpec("alt F2"));
        assertFalse(ShortcutsPanel.isValidKeySpec(""), "blank is rejected");
        assertFalse(ShortcutsPanel.isValidKeySpec("not a key"), "unparseable is rejected");
        assertFalse(ShortcutsPanel.isValidKeySpec(null), "null is rejected");
    }
}
