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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic.Kind;
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

    /**
     * Blocks until the EDT has drained every task posted before this call, so a
     * test can assert the outcome of work a virtual thread marshalled back via
     * {@code invokeLater} (Swing is headless-safe: the EDT still runs).
     */
    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
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
    @DisplayName("the bundled extensions install their 90 toolbar actions")
    void extensionsInstalled() {
        AdvancedTextEditorPanel panel = newPanel();
        // Text Tools 6 + Code Tools 6 + Case Tools 5 + Document Stats 1
        // + Java/Kotlin Tools 5 + Web Tools 4 + Formatting Tools 4
        // + Base64 Tools 2 + Markdown Tools 4 + JSON Tools 2
        // + Spring Boot Tools 4 + Hash Tools 3 + JVM Build Tools 6
        // + Java Structure Tools 4 + Java Debugger 6 + Java Completion 1
        // + Insert & Snippets 5 + TODO Scan 2 + Comment Toggle 3
        // + Mandela Tools 5 (run / check / bytecode / starter template / deck)
        // + Gradle Build Tools 6 + Maven Build Tools 6
        assertEquals(90, panel.extensionActionCount());
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
        // JVM Build Tools claim a distinct Ctrl+Alt+Shift family.
        assertTrue(panel.isAcceleratorBound("control alt shift J"));
        assertTrue(panel.isAcceleratorBound("control alt shift R"));
        assertTrue(panel.isAcceleratorBound("control alt shift X"));
        // Mandela Tools claim the free letters of that same family: M run, A
        // check, B bytecode. The two starter-template actions (script, deck) have
        // no accelerator.
        assertTrue(panel.isAcceleratorBound("control alt shift M"));
        assertTrue(panel.isAcceleratorBound("control alt shift A"));
        assertTrue(panel.isAcceleratorBound("control alt shift B"));
        // Gradle Build Tools claim G build, T test, U run, C clean, S tasks;
        // Maven Build Tools claim E compile, I test, P package, W run, Q clean.
        // Both outline actions stay unbound.
        assertTrue(panel.isAcceleratorBound("control alt shift G"));
        assertTrue(panel.isAcceleratorBound("control alt shift T"));
        assertTrue(panel.isAcceleratorBound("control alt shift U"));
        assertTrue(panel.isAcceleratorBound("control alt shift E"));
        assertTrue(panel.isAcceleratorBound("control alt shift I"));
        assertTrue(panel.isAcceleratorBound("control alt shift P"));
        // No bundled action claims Ctrl+Alt+0, so it stays unbound.
        assertFalse(panel.isAcceleratorBound("control alt 0"));
        panel.dispose();
    }

    @Test
    @DisplayName("extensionInfos lists every installed extension as enabled")
    void extensionInfosListed() {
        AdvancedTextEditorPanel panel = newPanel();
        List<ExtensionsCard.ExtensionInfo> infos = panel.extensionInfos();
        assertEquals(23, infos.size());
        assertTrue(infos.stream().allMatch(ExtensionsCard.ExtensionInfo::enabled),
                "built-in extensions start enabled");
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.spring-boot-tools")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.jvm-build-tools")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.java-diagnostics")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.java-structure")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.java-debug")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.java-completion")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.snippets")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.todo")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.comment")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.mandela")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.gradle-build")));
        assertTrue(infos.stream().anyMatch(i -> i.id().equals("lg3d.maven-build")));
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
    @DisplayName("a build's project-wide report paints each file's own tab, not the focused one")
    void projectWideDiagnosticFanOut(@TempDir Path dir) throws IOException {
        // Plain .txt on purpose: a JVM source file would be picked up by the
        // bundled Java analyzer the moment its tab opens, and its asynchronous
        // report would repaint the tab behind this test's back. A build's findings
        // are not tied to an extension, so the routing under test is the same.
        Path app = Files.writeString(dir.resolve("App.txt"), "one\ntwo\nthree\n");
        Path broken = Files.writeString(dir.resolve("Broken.txt"), "a\nb\nc\nd\n");
        Path closed = dir.resolve("Closed.txt");
        Files.writeString(closed, "x\ny\nz\n");
        AdvancedTextEditorPanel panel = newPanel();
        try {
            panel.openPath(app);
            panel.openPath(broken);
            EditorTab appTab = tabFor(panel, app);
            EditorTab brokenTab = tabFor(panel, broken);

            panel.reportDiagnosticsForExtension(app.toString(), List.of(
                    Diagnostic.of(broken.toString(), 2, 1, Kind.ERROR, "cannot find symbol"),
                    Diagnostic.of(app.toString(), 3, 1, Kind.WARNING, "unused variable")));

            assertEquals(1, appTab.diagnosticCount(), "App.java owns only its own finding");
            assertEquals(1, brokenTab.diagnosticCount());
            assertEquals(Kind.WARNING, appTab.diagnosticAt(3));
            assertEquals(Kind.ERROR, brokenTab.diagnosticAt(2));
            assertEquals(2, panel.problemsPanel().problemCount(), "Problems lists the union");
            assertEquals("1 error(s), 1 warning(s)", panel.statusMessage());

            // A later build that no longer complains about Broken.java un-marks
            // it, and reports a file that has no tab at all.
            panel.reportDiagnosticsForExtension(app.toString(), List.of(
                    Diagnostic.of(app.toString(), 1, 1, Kind.ERROR, "boom"),
                    Diagnostic.of(closed.toString(), 2, 1, Kind.ERROR,
                            "unreachable statement")));
            assertEquals(0, brokenTab.diagnosticCount(), "a file the build likes is cleared");
            assertEquals(1, appTab.diagnosticCount());
            assertEquals(2, panel.problemsPanel().problemCount());
            assertEquals("2 error(s), 0 warning(s)", panel.statusMessage());

            // Activating a row whose file is closed opens it and paints its own
            // markers — the whole point of a Problems row being clickable.
            int before = panel.tabCount();
            panel.problemsPanel().activateRowAt(rowFor(panel, closed.toString()));
            assertEquals(before + 1, panel.tabCount());
            EditorTab closedTab = tabFor(panel, closed);
            assertSame(closedTab, panel.currentTab());
            assertEquals(1, closedTab.diagnosticCount());
            assertEquals(Kind.ERROR, closedTab.diagnosticAt(2));
            assertEquals("Ln 2, Col 1", panel.statusPosition());

            // One clear drops the whole project-wide picture, not just the tab
            // the build happened to name.
            panel.clearDiagnosticsForExtension(app.toString());
            assertEquals(0, appTab.diagnosticCount());
            assertEquals(0, closedTab.diagnosticCount());
            assertEquals(0, panel.problemsPanel().problemCount());
        } finally {
            panel.dispose();
        }
    }

    /** The open tab editing {@code file} (test helper over the real tab strip). */
    private static EditorTab tabFor(AdvancedTextEditorPanel panel, Path file) {
        JTabbedPane strip = panel.tabStrip();
        for (int i = 0; i < strip.getTabCount(); i++) {
            EditorTab tab = (EditorTab) strip.getComponentAt(i);
            if (file.equals(tab.getPath())) {
                return tab;
            }
        }
        throw new AssertionError("no tab for " + file);
    }

    /** The view row listing a finding on {@code path}, or -1. */
    private static int rowFor(AdvancedTextEditorPanel panel, String path) {
        for (int i = 0; i < panel.problemsPanel().problemCount(); i++) {
            Diagnostic d = panel.problemsPanel().diagnosticAt(i);
            if (d != null && path.equals(d.path())) {
                return i;
            }
        }
        throw new AssertionError("no Problems row for " + path);
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
    @DisplayName("every tab header carries a close button that closes that tab")
    void closableTabHeaders() {
        AdvancedTextEditorPanel panel = newPanel();
        EditorTab second = panel.newTab();
        JTabbedPane strip = panel.tabStrip();
        // Both tabs get a custom header component (the close affordance).
        for (int i = 0; i < strip.getTabCount(); i++) {
            assertNotNull(strip.getTabComponentAt(i), "header for tab " + i);
        }
        assertEquals(2, panel.tabCount());

        // The header of the second tab holds a JButton; firing it closes that
        // specific tab (index is resolved at click time), leaving the first.
        Component header = strip.getTabComponentAt(1);
        JButton close = findCloseButton(header);
        assertNotNull(close, "the header exposes a close button");
        close.doClick();
        assertEquals(1, panel.tabCount());
        assertNotSame(second, panel.currentTab(), "the closed tab is gone");
        panel.dispose();
    }

    /** Depth-first search of a tab header for its single close {@link JButton}. */
    private static JButton findCloseButton(Component comp) {
        if (comp instanceof JButton b) {
            return b;
        }
        if (comp instanceof java.awt.Container c) {
            for (Component child : c.getComponents()) {
                JButton found = findCloseButton(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    // -- Project menu and Git GUI integration (Phase 5) --------------------

    @Test
    @DisplayName("project names are validated before any folder is touched")
    void projectNames() {
        assertEquals("my-app", AdvancedTextEditorPanel.sanitizeProjectName("  my-app "));
        assertNull(AdvancedTextEditorPanel.sanitizeProjectName(null));
        assertNull(AdvancedTextEditorPanel.sanitizeProjectName("  "));
        assertNull(AdvancedTextEditorPanel.sanitizeProjectName(".."));
        assertNull(AdvancedTextEditorPanel.sanitizeProjectName("a/b"));
        assertNull(AdvancedTextEditorPanel.sanitizeProjectName("evil;rm"));
    }

    @Test
    @DisplayName("a project skeleton carries src/Main.java and a README")
    void projectSkeleton(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("demo");
        AdvancedTextEditorPanel.createProjectSkeleton(root);
        assertTrue(Files.isRegularFile(root.resolve("src/Main.java")));
        assertTrue(Files.readString(root.resolve("src/Main.java"))
                .contains("public class Main"));
        assertTrue(Files.isRegularFile(root.resolve("README.md")));
        // A non-empty directory is never clobbered.
        assertThrows(IOException.class,
                () -> AdvancedTextEditorPanel.createProjectSkeleton(root));
    }

    @Test
    @DisplayName("attaching a project roots the tree and persists the recents")
    void attachAndRecentProjects(@TempDir Path dir) {
        AdvancedTextEditorPanel panel = newPanel();
        Path root = dir.resolve("ws");
        panel.attachProject(root);
        assertEquals(root.toString(), panel.currentProjectRoot());
        assertEquals(List.of(root.toString()), panel.settings().getRecentProjects());

        panel.openRecentProject(root.toString());
        panel.openRecentProject(dir.resolve("gone").toString());
        assertTrue(panel.settings().getRecentProjects().stream()
                .noneMatch(p -> p.endsWith("gone")), "missing folders are forgotten");

        panel.removeRecentProject(root.toString());
        assertTrue(panel.settings().getRecentProjects().isEmpty());
        panel.attachProject(root);
        panel.clearRecentProjects();
        assertTrue(panel.settings().getRecentProjects().isEmpty());
        panel.attachProject(null); // no-op
        panel.dispose();
    }

    @Test
    @DisplayName("re-root, detach and the embedded Git GUI target the right folder")
    void projectChromeAndGit(@TempDir Path dir) throws IOException {
        AdvancedTextEditorPanel panel = newPanel();
        Path file = dir.resolve("Hello.java");
        Files.writeString(file, "class Hello { }\n");
        assertTrue(panel.openFile(file.toFile()));

        panel.reRootToCurrentFile();
        assertEquals(ProjectTreePanel.projectRootFor(file),
                panel.projectTree().rootPath());

        panel.closeProject();
        assertNotNull(panel.projectTree().rootPath());

        panel.attachProject(dir);
        panel.openGitGui();
        assertEquals(dir.toFile(), panel.gitTarget(),
                "the desktop Git client is pointed at the project root");
        assertNotNull(panel.gitGuiPanel());
        assertNotNull(panel.projectCard());
        panel.dispose();
    }

    @Test
    @DisplayName("New Project provisions off the EDT, then attaches and opens the entry file")
    void newProjectProvisionsAttachesAndOpens(@TempDir Path tmp) throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        // A shared JDK store holding one JDK 21 (never copied into the project) and
        // a provisioner that "downloads" a canned Gradle instead of touching the net.
        panel.setJdkRegistryForTesting(new JdkRegistry(jdkStore(tmp, "21.0.4"),
                (url, dest) -> {
                }));
        panel.setProvisionerForTesting(new ProjectProvisioner((url, dest) -> {
            String file = url.substring(url.lastIndexOf('/') + 1);
            String dir = file.replace("-bin.zip", "");
            CannedArchives.writeToolZip(dest, dir, dir.startsWith("gradle") ? "gradle" : "mvn");
        }));

        Path parent = tmp.resolve("ws");
        Thread worker = panel.provisionNewProject(parent,
                new NewProjectRequest("demo", BuildSystem.GRADLE, "8.10", "jdk-21.0.4+7"));
        worker.join(20_000);
        flushEdt();

        Path root = parent.resolve("demo");
        assertTrue(Files.isRegularFile(root.resolve("build.gradle")), "the project was scaffolded");
        assertTrue(Files.isExecutable(root.resolve(".espresso/tools/gradle-8.10/bin/gradle")),
                "the project's own Gradle was downloaded and made runnable");
        assertEquals(root.toString(), panel.currentProjectRoot(), "the new project is attached");
        assertEquals(2, panel.tabCount(), "the entry source opened in a new tab");
        assertTrue(panel.statusMessage().contains("created at"),
                "the status line reports success: " + panel.statusMessage());
        panel.dispose();
    }

    @Test
    @DisplayName("a provisioning failure reports once and attaches nothing")
    void newProjectFailureAttachesNothing(@TempDir Path tmp) throws Exception {
        AdvancedTextEditorPanel panel = newPanel();
        panel.setJdkRegistryForTesting(new JdkRegistry(jdkStore(tmp, "21.0.4"),
                (url, dest) -> {
                }));
        panel.setProvisionerForTesting(new ProjectProvisioner((url, dest) -> {
        }));
        // Keep the failure log out of the real home directory.
        Path errorLog = tmp.resolve("new-project-error.log");
        panel.setProvisioningErrorLogForTesting(errorLog);
        String rootBefore = panel.currentProjectRoot();

        // A JDK the manager does not know is rejected before anything is written.
        Path parent = tmp.resolve("ws");
        Thread worker = panel.provisionNewProject(parent,
                new NewProjectRequest("demo", BuildSystem.GRADLE, "8.10", "ghost-jdk"));
        worker.join(20_000);
        flushEdt();

        assertFalse(Files.exists(parent.resolve("demo")), "nothing was scaffolded");
        assertEquals(rootBefore, panel.currentProjectRoot(),
                "a failed provisioning attaches nothing");
        assertTrue(panel.statusMessage().contains("Could not create project"),
                "one status message explains the failure: " + panel.statusMessage());
        assertTrue(panel.statusMessage().contains("not in the manager"),
                "the short message names the root cause, not just the wrapper: "
                        + panel.statusMessage());
        assertTrue(Files.isRegularFile(errorLog),
                "the full stack trace is written where it can be read and copied");
        assertTrue(Files.readString(errorLog).contains("not in the manager"),
                "the log carries the whole cause chain");
        String console = panel.outputConsoleText();
        assertTrue(console.contains("New Project failed") && console.contains("IOException"),
                "the full trace is surfaced in the selectable, copyable console: " + console);
        panel.dispose();
    }

    /** A temp JDK-manager store holding one managed JDK 21 with the given version. */
    private static Path jdkStore(Path tmp, String version) throws IOException {
        Path home = tmp.resolve("jdkstore").resolve("jdks").resolve("jdk-21.0.4+7");
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/bin/sh\n");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return tmp.resolve("jdkstore");
    }

    @Test
    @DisplayName("a completion publish feeds both the strip and the inline popup")
    void completionFeedsBothSurfaces() throws BadLocationException {
        AdvancedTextEditorPanel panel = newPanel();
        EditorTab tab = panel.currentTab();
        type(tab, "cou"); // non-empty document so the trigger can show
        List<String> cands = List.of("counter", "continue");

        panel.showCompletionsForExtension("/src/Demo.java", cands);

        // The bottom strip always mirrors the full candidate list.
        assertEquals(2, panel.completionPanel().rowCount());
        assertEquals(cands, panel.lastCompletionCandidates());
        // The inline popup is populated when shown at the caret (Ctrl+Space force;
        // headless it composites without a layered pane, but the model is filled).
        panel.forceShowCompletion();
        assertEquals(2, panel.completionPopup().rowCount());

        // An empty publish clears both.
        panel.showCompletionsForExtension("/src/Demo.java", List.of());
        assertEquals(0, panel.completionPanel().rowCount());
        assertTrue(panel.lastCompletionCandidates().isEmpty());
        panel.dispose();
    }

    @Test
    @DisplayName("the toolbar-customise host persists a choice and renders its button")
    void toolbarCustomizeAppliesAndRenders() {
        AdvancedTextEditorPanel panel = newPanel();
        assertTrue(panel.userToolbarButtonCount() == 0, "no user button by default");
        // Every contributed command is offered under a provider-qualified key.
        assertTrue(panel.availableCommands().stream()
                .anyMatch(c -> c.id().equals("lg3d.snippets/ins-uuid")));

        panel.applyToolbar(List.of(new ToolbarButtonConfig.Entry(
                "lg3d.snippets/ins-uuid", ToolbarButtonConfig.DisplayMode.ICON_TEXT)));
        assertEquals(1, panel.settings().getToolbarButtons().size());
        assertEquals(1, panel.userToolbarButtonCount());

        // A stale id (an extension that is not available) never renders a button,
        // but applyToolbar still records it, so disabling an extension is safe.
        panel.applyToolbar(List.of(new ToolbarButtonConfig.Entry(
                "lg3d.gone/nothing", ToolbarButtonConfig.DisplayMode.ICON)));
        assertEquals(1, panel.settings().getToolbarButtons().size());
        assertEquals(0, panel.userToolbarButtonCount());
        panel.dispose();
    }

    @Test
    @DisplayName("the command row keeps every user button inside the 960px window")
    void userToolbarButtonsAreNotClipped() {
        AdvancedTextEditorPanel panel = newPanel();
        // Two icon+text commands, so the built-in row alone would already overflow:
        // appended to it they used to be laid out past the right edge and were
        // neither visible nor clickable.
        panel.applyToolbar(List.of(
                new ToolbarButtonConfig.Entry("lg3d.snippets/ins-uuid",
                        ToolbarButtonConfig.DisplayMode.ICON_TEXT),
                new ToolbarButtonConfig.Entry("lg3d.comment/toggle-comment",
                        ToolbarButtonConfig.DisplayMode.ICON_TEXT)));
        assertEquals(2, panel.userToolbarButtonCount());

        panel.setSize(AdvancedTextEditorPanel.WIDTH_PX,
                AdvancedTextEditorPanel.HEIGHT_PX);
        layoutAll(panel);

        JComponent strip = panel.userCommandStrip();
        assertNotNull(strip, "the second toolbar row exists");
        assertEquals("userToolbarStrip", strip.getName());
        assertFalse(strip.getParent() instanceof JToolBar,
                "the command row is its own row, not the tail of the built-in bar");

        for (JButton button : panel.userToolbarButtons()) {
            assertTrue(button.getHeight() > 0, button.getName() + " is laid out");
            assertTrue(rightIn(strip, button) > 0, button.getName() + " is on-screen");
            assertTrue(rightIn(strip, button) <= strip.getWidth(),
                    button.getName() + " fits inside the row");
        }
        JButton customize = named(panel, "customizeToolbarButton", JButton.class);
        assertNotNull(customize);
        assertTrue(rightIn(strip, customize) <= strip.getWidth(),
                "Customize stays reachable whatever the user adds");
        assertFalse(named(panel, "userToolbarHint", JLabel.class).isVisible(),
                "the empty-state hint steps aside once commands render");
        panel.dispose();
    }

    @Test
    @DisplayName("the command row shows its hint while no command is configured")
    void emptyCommandRowShowsHint() {
        AdvancedTextEditorPanel panel = newPanel();
        JLabel hint = named(panel, "userToolbarHint", JLabel.class);
        assertNotNull(hint, "the empty-state hint is present");
        assertTrue(hint.isVisible(), "and shown while the toolbar has no command");
        panel.applyToolbar(List.of(new ToolbarButtonConfig.Entry(
                "lg3d.snippets/ins-uuid", ToolbarButtonConfig.DisplayMode.ICON)));
        assertFalse(hint.isVisible(), "hidden once a command renders");
        panel.dispose();
    }

    /** Runs every container's layout manager at its current bounds, recursively. */
    private static void layoutAll(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container inner) {
                layoutAll(inner);
            }
        }
    }

    /** The right edge of {@code c} expressed in {@code anchor}'s x coordinate. */
    private static int rightIn(Component anchor, Component c) {
        int x = c.getX() + c.getWidth();
        for (Component p = c.getParent(); p != null && p != anchor; p = p.getParent()) {
            x += p.getX();
        }
        return x;
    }

    /** Finds a component of type {@code type} by {@code getName()} anywhere below {@code root}. */
    private static <T extends Component> T named(Container root, String name,
            Class<T> type) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) {
                return type.cast(c);
            }
            if (c instanceof Container inner) {
                T found = named(inner, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
