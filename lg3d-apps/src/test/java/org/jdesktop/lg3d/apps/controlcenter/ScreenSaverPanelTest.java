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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Screen Saver panel.
 *
 * <p>Building {@link ScreenSaverPanel} reads the persisted
 * {@code ScreensaverSettings} and enumerates the installed savers through
 * {@code ScreenSaverRegistry.discover()} (which always yields at least the core
 * {@code BlankSaver}). It realises no top-level window, and the only thing that
 * needs a displayable screen - the embedded live preview - is skipped when no
 * {@code ScreensaverService} runs (the headless case), so constructing and
 * showing the panel under {@code java.awt.headless=true} is CI-safe.</p>
 */
class ScreenSaverPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        ScreenSaverPanel panel = assertDoesNotThrow(ScreenSaverPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        ScreenSaverPanel panel = new ScreenSaverPanel();
        assertEquals("Screen Saver", panel.displayName());
        assertNull(panel.icon(), "the Screen Saver panel ships no navigation icon");
    }

    @Test
    @DisplayName("the component is stable across show/hide and re-loads")
    void componentAndLifecycle() {
        ScreenSaverPanel panel = new ScreenSaverPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout());
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the panel lays out timing, selection, options and preview blocks")
    void sectionsArePresent() {
        ScreenSaverPanel panel = new ScreenSaverPanel();
        assertNotNull(findTitledPanel(panel.component(), "When"),
                "the enable/timeout section");
        assertNotNull(findTitledPanel(panel.component(), "Saver"),
                "the saver picker section");
        assertNotNull(findTitledPanel(panel.component(), "Saver options"),
                "the generic per-saver options section");
        assertNotNull(findTitledPanel(panel.component(), "Preview"),
                "the live preview pane");
    }

    @Test
    @DisplayName("the panel offers Test, Apply and Reset actions")
    void actionButtons() {
        ScreenSaverPanel panel = new ScreenSaverPanel();
        List<String> buttons = buttonTexts(panel.component());
        assertTrue(buttons.contains("Test"), "the Test button engages a timed saver");
        assertTrue(buttons.contains("Apply"), "Apply persists and re-arms the saver");
        assertTrue(buttons.contains("Reset to defaults"));
    }

    // ------------------------------------------------------------------
    // Headless Swing-tree helpers (no window is realised)
    // ------------------------------------------------------------------

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
