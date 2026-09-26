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

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Schedule panel.
 *
 * <p>Building {@link SchedulePanel} reads the schedule fields from
 * {@code DesktopConfig} and enumerates the bundled wallpapers (falling back to
 * a fixed list when the background directory is not on the classpath, as in the
 * test JVM). It creates only lightweight Swing components - no top-level window
 * - so constructing it under {@code java.awt.headless=true} (set by the module's
 * test task) is CI-safe and needs no display.</p>
 */
class SchedulePanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        SchedulePanel panel = assertDoesNotThrow(SchedulePanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        SchedulePanel panel = new SchedulePanel();
        assertEquals("Schedule", panel.displayName());
        assertNull(panel.icon(), "the Schedule panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a Swing component and re-loads on show")
    void componentAndOnShow() {
        SchedulePanel panel = new SchedulePanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        // The component is stable across shows (created once, reused).
        assertEquals(component, panel.component());
    }

    // ------------------------------------------------------------------
    // Two independent sections: wallpaper entries vs. photo-free lighting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the wallpaper section carries the entry list plus Add/Remove")
    void wallpaperSectionHasEntryListAndAddRemove() {
        SchedulePanel panel = new SchedulePanel();
        JPanel wallpaper = findTitledPanel(panel.component(), "Wallpaper schedule");
        assertNotNull(wallpaper, "the wallpaper section is its own titled block");

        // On/off selector + the variable-length entry list + the per-entry
        // wallpaper selector, and nothing shared with the lighting section.
        assertEquals(3, collect(wallpaper, JList.class).size(),
                "on/off, entry list and per-entry wallpaper selectors");

        List<String> buttons = buttonTexts(wallpaper);
        assertTrue(buttons.contains("Add"), "entries can be added");
        assertTrue(buttons.contains("Remove"), "entries can be removed");
    }

    @Test
    @DisplayName("the lighting section has its own times and no wallpaper list")
    void lightingSectionHasNoWallpaperList() {
        SchedulePanel panel = new SchedulePanel();
        JPanel lighting = findTitledPanel(panel.component(), "Lighting schedule");
        assertNotNull(lighting, "the lighting section is its own titled block");

        // Only the on/off JList lives here - no entry list, no wallpaper list.
        assertEquals(1, collect(lighting, JList.class).size(),
                "just the on/off selector; the lighting schedule carries no photos");
        // The spinners contribute their own arrow JButtons, so assert on labels:
        // the lighting section has no Add/Remove entry controls.
        List<String> buttons = buttonTexts(lighting);
        assertFalse(buttons.contains("Add"), "no wallpaper entries to add");
        assertFalse(buttons.contains("Remove"), "no wallpaper entries to remove");
    }

    @Test
    @DisplayName("the two sections are separate blocks with shared Apply buttons")
    void sectionsAreIndependentWithApplyButtons() {
        SchedulePanel panel = new SchedulePanel();
        JPanel wallpaper = findTitledPanel(panel.component(), "Wallpaper schedule");
        JPanel lighting = findTitledPanel(panel.component(), "Lighting schedule");
        assertNotNull(wallpaper);
        assertNotNull(lighting);
        assertTrue(wallpaper != lighting, "wallpaper and lighting are distinct panels");

        List<String> buttons = buttonTexts(panel.component());
        assertTrue(buttons.contains("Apply Schedule"));
        assertTrue(buttons.contains("Apply Now"));
    }

    // ------------------------------------------------------------------
    // Headless Swing-tree helpers (no window is realised)
    // ------------------------------------------------------------------

    /** Every descendant of {@code root} assignable to {@code type}, in tree order. */
    private static <T extends Component> List<T> collect(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectInto(root, type, out);
        return out;
    }

    private static <T extends Component> void collectInto(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) {
            out.add(type.cast(c));
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectInto(child, type, out);
            }
        }
    }

    /** The first {@code JPanel} whose titled border contains {@code fragment}. */
    private static JPanel findTitledPanel(Component root, String fragment) {
        for (JPanel p : collect(root, JPanel.class)) {
            if (p.getBorder() instanceof TitledBorder titled
                    && titled.getTitle() != null
                    && titled.getTitle().contains(fragment)) {
                return p;
            }
        }
        return null;
    }

    private static List<String> buttonTexts(Component root) {
        List<String> texts = new ArrayList<>();
        for (JButton b : collect(root, JButton.class)) {
            texts.add(b.getText());
        }
        return texts;
    }
}
