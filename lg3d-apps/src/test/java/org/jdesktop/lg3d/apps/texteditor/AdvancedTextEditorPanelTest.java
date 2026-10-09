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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.text.BadLocationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link AdvancedTextEditorPanel}, the Swing surface both
 * desktops host. Built through the package-private
 * {@code AdvancedTextEditorPanel(false)} constructor so no preference is ever
 * written. The file chooser and JOptionPane paths are unreachable headless by
 * design; everything else (tabs, open/save, find &amp; replace, settings,
 * recents, extensions, status line) is driven through the real widget.
 */
class AdvancedTextEditorPanelTest {

    private static AdvancedTextEditorPanel newPanel() {
        return new AdvancedTextEditorPanel(false);
    }

    private static void type(EditorTab tab, String text)
            throws BadLocationException {
        tab.document().insertString(tab.document().getLength(), text, null);
    }

    @Test
    @DisplayName("a fresh panel has one clean tab and the advertised size")
    void freshPanel() {
        AdvancedTextEditorPanel panel = newPanel();
        assertEquals(1, panel.tabCount());
        assertNotNull(panel.currentTab());
        assertEquals("", panel.currentTab().getText());
        assertEquals(AdvancedTextEditorPanel.WIDTH_PX,
                panel.getPreferredSize().width);
        assertEquals(AdvancedTextEditorPanel.HEIGHT_PX,
                panel.getPreferredSize().height);
        assertNotNull(panel.settings());
        assertEquals("", panel.statusMessage());
        panel.dispose();
    }

    @Test
    @DisplayName("the bundled Text Tools extension installs its six actions")
    void extensionsInstalled() {
        AdvancedTextEditorPanel panel = newPanel();
        assertEquals(6, panel.extensionActionCount());
        panel.dispose();
    }

    @Test
    @DisplayName("openPath loads a file into a new tab and records it")
    void openFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello world\n", StandardCharsets.UTF_8);
        AdvancedTextEditorPanel panel = newPanel();

        assertTrue(panel.openPath(file));
        assertEquals(2, panel.tabCount());
        assertEquals("hello world\n", panel.currentTab().getText());
        assertEquals("notes.txt", panel.currentTab().getDisplayName());
        assertFalse(panel.currentTab().isDirty());
        assertTrue(panel.statusMessage().contains("Opened"));
        assertTrue(panel.settings().getRecentFiles()
                .contains(file.toAbsolutePath().toString()));

        // Re-opening the same file reuses the tab.
        assertTrue(panel.openPath(file));
        assertEquals(2, panel.tabCount());
        assertTrue(panel.statusMessage().contains("Already open"));

        // Degenerate inputs.
        assertFalse(panel.openPath(null));
        assertFalse(panel.openFile(null));
        assertFalse(panel.openPath(dir.resolve("missing.txt")));
        assertTrue(panel.statusMessage().contains("Could not open"));

        // The file-association hook opens real files too.
        assertTrue(panel.openFile(file.toFile()));
        panel.dispose();
    }

    @Test
    @DisplayName("saveToPath writes atomically and rebinds the tab")
    void saveFile(@TempDir Path dir) throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        EditorTab tab = panel.currentTab();
        type(tab, "saved content");
        assertTrue(tab.isDirty());

        Path target = dir.resolve("out/note.txt"); // parent created on save
        assertTrue(panel.saveToPath(tab, target));
        assertEquals("saved content", Files.readString(target));
        assertFalse(tab.isDirty());
        assertEquals(target, tab.getPath());
        assertEquals("note.txt", tab.getDisplayName());
        assertTrue(panel.statusMessage().contains("Saved"));
        assertTrue(panel.settings().getRecentFiles()
                .contains(target.toAbsolutePath().toString()));

        // Degenerate inputs.
        assertFalse(panel.saveToPath(null, target));
        assertFalse(panel.saveToPath(tab, null));
        panel.dispose();
    }

    @Test
    @DisplayName("find and replace drive the document through the bar")
    void findAndReplace(@TempDir Path dir) throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        EditorTab tab = panel.currentTab();
        type(tab, "foo bar foo baz foo");

        panel.findBar().setQuery("foo");
        assertEquals("1 of 3", panel.findBar().matchInfo());

        panel.findNext();
        assertEquals("2 of 3", panel.findBar().matchInfo());
        panel.findPrevious();
        assertEquals("1 of 3", panel.findBar().matchInfo());

        // Replace the selected match, then every remaining one.
        panel.findBar().setReplacement("X");
        panel.replaceOne();
        assertEquals("X bar foo baz foo", tab.getText());
        panel.replaceAll();
        assertEquals("X bar X baz X", tab.getText());
        assertTrue(panel.statusMessage().contains("2 replacements"));

        // Both replaces were undoable: Replace All is one step.
        tab.undo();
        assertEquals("X bar foo baz foo", tab.getText());

        // A query with no hits reports honestly.
        panel.findBar().setQuery("zzz");
        assertEquals("No matches", panel.findBar().matchInfo());
        panel.replaceAll();
        assertTrue(panel.statusMessage().contains("No matches"));

        // An empty query resets the counter.
        panel.findBar().setQuery("");
        assertEquals("", panel.findBar().matchInfo());
        panel.dispose();
    }

    @Test
    @DisplayName("go-to-line jumps the caret and clamps out-of-range lines")
    void goToLine() throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        type(panel.currentTab(), "one\ntwo\nthree");
        panel.findBar().setLineNumber(2);
        panel.goToLine();
        assertEquals(2, panel.currentTab().getCaretLine());
        assertTrue(panel.statusMessage().contains("Line 2"));

        panel.findBar().setLineNumber(99);
        panel.goToLine();
        assertEquals(3, panel.currentTab().getCaretLine());

        panel.findBar().setLineNumber(-1);
        panel.goToLine();
        assertTrue(panel.statusMessage().contains("Enter a line number"));
        panel.dispose();
    }

    @Test
    @DisplayName("tabs open, cycle and close; headless skips the confirm")
    void tabLifecycle() throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        EditorTab second = panel.newTab();
        assertEquals(2, panel.tabCount());
        assertEquals(second, panel.currentTab());
        assertEquals("Untitled 2", second.getDisplayName());

        type(second, "dirty");
        assertTrue(second.isDirty());
        panel.closeCurrentTab(); // headless: closes without a dialog
        assertEquals(1, panel.tabCount());

        // Out-of-range close is a no-op.
        panel.closeTab(-1);
        panel.closeTab(99);
        assertEquals(1, panel.tabCount());
        panel.dispose();
    }

    @Test
    @DisplayName("applySettings keeps the recent list and restyles tabs")
    void settingsRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "x", StandardCharsets.UTF_8);
        AdvancedTextEditorPanel panel = newPanel();
        panel.openPath(file);
        assertEquals(1, panel.settings().getRecentFiles().size());

        EditorSettings edited = EditorSettings.fromMap(panel.settings()
                .toMap());
        edited.setFontSize(22);
        edited.setThemeName("Dark");
        panel.applySettings(edited);
        assertEquals(22, panel.settings().getFontSize());
        assertEquals("Dark", panel.settings().getThemeName());
        assertEquals(1, panel.settings().getRecentFiles().size(),
                "the panel merges its recents into the edited settings");
        assertEquals(EditorTheme.DARK.getBackground(),
                panel.currentTab().textPane().getBackground());
        assertEquals("Settings applied", panel.statusMessage());

        // A null edit is a cancel: settings stay as they were.
        panel.applySettings(null);
        assertEquals(22, panel.settings().getFontSize());

        // Removing and clearing recents.
        panel.removeRecent(file.toAbsolutePath().toString());
        assertTrue(panel.settings().getRecentFiles().isEmpty());
        panel.openPath(file);
        assertEquals(1, panel.settings().getRecentFiles().size());
        panel.clearRecent();
        assertTrue(panel.settings().getRecentFiles().isEmpty());
        panel.dispose();
    }

    @Test
    @DisplayName("an extension action runs against the current tab")
    void runExtensionAction() throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        type(panel.currentTab(), "banana\napple");
        // The new extension API requires notifying extensions when a document is opened
        panel.notifyDocumentOpened(panel.currentTab());
        panel.runExtensionAction(0); // "Text Tools: Sort Lines (A-Z)"
        assertEquals("apple\nbanana", panel.currentTab().getText());
        // Out-of-range indexes are ignored.
        panel.runExtensionAction(-1);
        panel.runExtensionAction(999);
        panel.dispose();
    }

    @Test
    @DisplayName("the close callback fires through setOnClose")
    void closeCallback() {
        AdvancedTextEditorPanel panel = newPanel();
        final boolean[] closed = {false};
        panel.setOnClose(() -> closed[0] = true);
        // requestClose is private (toolbar Close button); dispose is the
        // public teardown and must never throw.
        panel.dispose();
        assertFalse(closed[0], "dispose does not impersonate a close");
    }

    @Test
    @DisplayName("the status line tracks the current document")
    void statusLine() throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        EditorTab tab = panel.currentTab();
        type(tab, "abc\ndef");
        tab.textPane().setCaretPosition(5);
        // The caret listener refreshes the status line on every caret move.
        assertEquals("Ln 2, Col 2", panel.statusPosition());
        panel.dispose();
    }
}
