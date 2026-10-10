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
import java.util.List;
import javax.swing.text.BadLocationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /** A throwaway config dir so enable/permission writes never touch ~/.lg3d. */
    @TempDir
    Path storeDir;

    @BeforeEach
    void isolateExtensionStore() {
        System.setProperty(EditorStore.DIR_PROPERTY, storeDir.toString());
    }

    @AfterEach
    void restoreExtensionStore() {
        System.clearProperty(EditorStore.DIR_PROPERTY);
    }

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
    @DisplayName("the bundled extensions install their 52 toolbar actions")
    void extensionsInstalled() {
        AdvancedTextEditorPanel panel = newPanel();
        // Text Tools 6 + Code Tools 6 + Case Tools 5 + Document Stats 1
        // + Java/Kotlin Tools 5 + Web Tools 4 + Formatting Tools 4
        // + Base64 Tools 2 + Markdown Tools 4 + JSON Tools 2
        // + Spring Boot Tools 4 + Hash Tools 3 + JVM Build Tools 6
        assertEquals(52, panel.extensionActionCount());
        panel.dispose();
    }

    @Test
    @DisplayName("extension accelerators bind to the editor input map")
    void extensionAcceleratorsBound() {
        AdvancedTextEditorPanel panel = newPanel();
        // Declared by BuiltinTextTools sort-az, CodeTools indent, JsonTools
        // prettify, SpringBootTools props-to-yaml, SpringBootTools normalize-keys
        // and SpringBootTools list-placeholders, respectively.
        assertTrue(panel.isAcceleratorBound("control alt A"));
        assertTrue(panel.isAcceleratorBound("control alt R"));
        assertTrue(panel.isAcceleratorBound("control alt O"));
        assertTrue(panel.isAcceleratorBound("control alt S"));
        assertTrue(panel.isAcceleratorBound("control alt 8"));
        assertTrue(panel.isAcceleratorBound("control alt 9"));
        // No bundled action claims Ctrl+Alt+0, so it stays unbound.
        assertFalse(panel.isAcceleratorBound("control alt 0"));
        panel.dispose();
    }

    @Test
    @DisplayName("extensionInfos lists every installed extension as enabled")
    void extensionInfosListed() {
        AdvancedTextEditorPanel panel = newPanel();
        List<ExtensionsCard.ExtensionInfo> infos = panel.extensionInfos();
        assertEquals(14, infos.size());
        assertTrue(infos.stream().allMatch(ExtensionsCard.ExtensionInfo::enabled),
                "built-in extensions start enabled");
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.spring-boot-tools")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.jvm-build-tools")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.java-diagnostics")));
        panel.dispose();
    }

    @Test
    @DisplayName("extension actions run against the live text, not the open-time snapshot")
    void extensionActionSeesLiveText() throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        EditorTab tab = panel.currentTab();
        // The tab was opened empty; the snapshot an extension holds would
        // otherwise stay empty and every action would silently no-op.
        type(tab, "b\na");
        panel.runExtensionAction(0); // BuiltinTextTools sort-az, whole document
        assertEquals("a\nb", tab.getText());
        panel.dispose();
    }

    @Test
    @DisplayName("a rebound accelerator follows the override and frees the old key")
    void acceleratorRebind() {
        AdvancedTextEditorPanel panel = newPanel();
        // Index 0 is BuiltinTextTools sort-az, declared on Ctrl+Alt+A.
        assertEquals("control alt A", panel.getAccelerator(0));

        panel.setAccelerator(0, "control alt 0");
        assertTrue(panel.isAcceleratorBound("control alt 0"));
        assertFalse(panel.isAcceleratorBound("control alt A"));
        assertEquals("control alt 0", panel.getAccelerator(0));
        assertEquals("control alt 0",
                panel.settings().getAcceleratorOverrides().get("sort-az"));

        // Setting the declared default back clears the override.
        panel.setAccelerator(0, "control alt A");
        assertTrue(panel.isAcceleratorBound("control alt A"));
        assertFalse(panel.isAcceleratorBound("control alt 0"));
        assertFalse(panel.settings().getAcceleratorOverrides().containsKey("sort-az"));

        // An empty spec unbinds the action entirely.
        panel.setAccelerator(0, "");
        assertFalse(panel.isAcceleratorBound("control alt A"));
        assertEquals("", panel.getAccelerator(0));
        panel.dispose();
    }

    @Test
    @DisplayName("revoking TOOLBAR hides an extension's actions; regranting restores them")
    void permissionGrantControlsToolbar() {
        AdvancedTextEditorPanel panel = newPanel();
        int before = panel.extensionActionCount();
        var perms = panel.permissionsFor("lg3d.base64-tools");
        assertEquals(3, perms.size(), "READ, WRITE, TOOLBAR");
        assertTrue(perms.stream().anyMatch(p -> p.name().equals("TOOLBAR") && p.granted()));

        panel.setPermission("lg3d.base64-tools", "TOOLBAR", false);
        assertEquals(before - 2, panel.extensionActionCount());
        assertFalse(panel.isAcceleratorBound("control alt Q"));
        assertTrue(panel.permissionsFor("lg3d.base64-tools").stream()
                .anyMatch(p -> p.name().equals("TOOLBAR") && !p.granted()));

        panel.setPermission("lg3d.base64-tools", "TOOLBAR", true);
        assertEquals(before, panel.extensionActionCount());
        assertTrue(panel.isAcceleratorBound("control alt Q"));
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

    // -- south output console and west project tree --------------------------

    @Test
    @DisplayName("extension tool output lands in the south console, not the document")
    void outputConsoleReceivesToolOutput() {
        AdvancedTextEditorPanel panel = newPanel();
        panel.showOutputForExtension("javac output", "Foo.java:1: error: ';'");
        assertTrue(panel.outputConsoleText().contains("---- javac output ----\n"
                + "Foo.java:1: error: ';'"), panel.outputConsoleText());
        assertEquals("", panel.currentTab().getText(),
                "the console never writes into the document");
        panel.clearOutputConsole();
        assertEquals("", panel.outputConsoleText());
        panel.dispose();
    }

    @Test
    @DisplayName("opening a file re-roots the west project tree on its project")
    void projectTreeFollowsOpenedDocument(@TempDir Path project)
            throws IOException {
        Files.createDirectory(project.resolve(".git"));
        Path src = Files.createDirectories(project.resolve("src/main"));
        Path file = src.resolve("Foo.java");
        Files.writeString(file, "public class Foo { }\n");
        AdvancedTextEditorPanel panel = newPanel();
        assertTrue(panel.openPath(file));
        assertEquals(project, panel.projectTree().rootPath(),
                "the tree walked up from src/main to the .git project root");
        panel.dispose();
    }

    @Test
    @DisplayName("a tree file selection opens the file in a new tab")
    void projectTreeOpensFiles(@TempDir Path project) throws IOException {
        Path first = project.resolve("a.txt");
        Files.writeString(first, "a\n");
        Path second = project.resolve("b.txt");
        Files.writeString(second, "b\n");
        AdvancedTextEditorPanel panel = newPanel();
        assertTrue(panel.openPath(first));       // roots the tree on the project
        assertEquals(2, panel.tabCount());
        assertTrue(panel.projectTree().revealAndOpen(second),
                "the file is visible under the current root");
        assertEquals(3, panel.tabCount());
        assertEquals("b", panel.currentTab().getText().strip());
        panel.dispose();
    }
}
