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
import javax.swing.JList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Task Scheduler panel, plus
 * unit tests for its command-line tokeniser (the security-critical splitter that
 * turns a typed command into an argv vector handed to {@code ProcessBuilder}).
 *
 * <p>Building the panel only reads the scheduler's task list and creates
 * lightweight Swing components - no top-level window - so it is CI-safe under
 * {@code java.awt.headless=true} and writes no preferences.</p>
 */
class TaskSchedulerPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void constructsHeadless() {
        TaskSchedulerPanel panel = assertDoesNotThrow(TaskSchedulerPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        TaskSchedulerPanel panel = new TaskSchedulerPanel();
        assertEquals("Task Scheduler", panel.displayName());
        assertNull(panel.icon());
    }

    @Test
    @DisplayName("the panel exposes a stable component and re-loads on show")
    void componentAndOnShow() {
        TaskSchedulerPanel panel = new TaskSchedulerPanel();
        JComponent c = panel.component();
        assertNotNull(c);
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(c, panel.component(), "the component is created once and reused");
    }

    @Test
    @DisplayName("the panel offers New/Delete/Save/Run now/Refresh and JList selectors")
    void controls() {
        TaskSchedulerPanel panel = new TaskSchedulerPanel();
        List<String> buttons = collect(panel.component(), JButton.class).stream()
                .map(JButton::getText).toList();
        assertTrue(buttons.contains("New"));
        assertTrue(buttons.contains("Delete"));
        assertTrue(buttons.contains("Save"));
        assertTrue(buttons.contains("Run now"));
        assertTrue(buttons.contains("Refresh"));
        // Choice controls are JList selectors (never combo boxes) for SwingNode.
        assertTrue(collect(panel.component(), JList.class).size() >= 6);
        assertEquals(0, collect(panel.component(), javax.swing.JComboBox.class).size(),
                "no combo boxes: the Control Center is rendered offscreen in a SwingNode");
    }

    @Test
    @DisplayName("the tokeniser splits on whitespace and honours double quotes")
    void tokenize() {
        TaskSchedulerPanel panel = new TaskSchedulerPanel();
        assertEquals(List.of("/bin/echo", "hi"), panel.tokenizeForTest("/bin/echo hi"));
        assertEquals(List.of("prog", "a b", "c"),
                panel.tokenizeForTest("prog \"a b\" c"));
        assertEquals(List.of(), panel.tokenizeForTest("   "));
        assertEquals(List.of(), panel.tokenizeForTest(null));
        assertEquals(List.of("prog", "; rm -rf ~"),
                panel.tokenizeForTest("prog \"; rm -rf ~\""),
                "shell metacharacters are just literal argument text");
    }

    @Test
    @DisplayName("tokenize and joinArgv round-trip a command line")
    void tokenizeJoinRoundTrip() {
        List<String> argv = List.of("/usr/bin/tar", "-cf", "/tmp/my backup.tar", "/home");
        String line = TaskSchedulerPanel.joinArgv(argv);
        assertEquals(argv, TaskSchedulerPanel.tokenize(line));
    }

    // --- Headless Swing-tree helpers -----------------------------------

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
}
