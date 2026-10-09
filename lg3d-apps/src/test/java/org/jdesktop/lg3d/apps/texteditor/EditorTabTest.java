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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.swing.text.BadLocationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link EditorTab}: construction defaults, content
 * loading with metadata, dirty tracking, the single-undo-step whole-text
 * replace, caret geometry and settings application. A tab is a plain Swing
 * component, so it constructs and behaves with {@code java.awt.headless=true}.
 */
class EditorTabTest {

    private static EditorTab newTab() {
        return new EditorTab(EditorSettings.defaults());
    }

    private static void type(EditorTab tab, String text)
            throws BadLocationException {
        tab.document().insertString(tab.document().getLength(), text, null);
    }

    @Test
    @DisplayName("a fresh tab is empty, clean and untitled")
    void freshTab() {
        EditorTab tab = newTab();
        assertEquals("", tab.getText());
        assertFalse(tab.isDirty());
        assertNull(tab.getPath());
        assertEquals("Untitled", tab.getDisplayName());
        assertSame(Languages.PLAIN, tab.getLanguage());
        assertEquals(StandardCharsets.UTF_8, tab.getCharset());
        assertEquals(TextFileIO.EOL_LF, tab.getEol());
        assertEquals(1, tab.getLineCount());
        assertEquals(1, tab.getCaretLine());
        assertEquals(1, tab.getCaretColumn());
        assertSame(EditorTheme.LIGHT, tab.theme());
        assertFalse(tab.canUndo());
        assertFalse(tab.canRedo());
    }

    @Test
    @DisplayName("typing dirties the tab; markSaved cleans it")
    void dirtyTracking() throws BadLocationException {
        EditorTab tab = newTab();
        final boolean[] notified = {false};
        tab.setDirtyListener(() -> notified[0] = true);
        type(tab, "hello");
        assertTrue(tab.isDirty());
        assertTrue(notified[0]);
        tab.markSaved();
        assertFalse(tab.isDirty());
    }

    @Test
    @DisplayName("loadContent installs text and metadata without dirtying")
    void loadContent() {
        EditorTab tab = newTab();
        tab.loadContent("class X {}\n", Path.of("/src/X.java"),
                StandardCharsets.UTF_8, TextFileIO.EOL_CRLF);
        assertEquals("class X {}\n", tab.getText());
        assertFalse(tab.isDirty());
        assertEquals("X.java", tab.getDisplayName());
        assertEquals("Java", tab.getLanguage().getName());
        assertEquals(TextFileIO.EOL_CRLF, tab.getEol());
        assertFalse(tab.canUndo(), "loading discards the undo history");
        // A null path keeps the tab untitled and plain.
        EditorTab untitled = newTab();
        untitled.loadContent("text", null, null, null);
        assertEquals("Untitled", untitled.getDisplayName());
        assertSame(Languages.PLAIN, untitled.getLanguage());
    }

    @Test
    @DisplayName("setPath re-derives the language and display name")
    void setPathRebinds() throws BadLocationException {
        EditorTab tab = newTab();
        type(tab, "x = 1");
        tab.setUntitledName("Draft");
        assertEquals("Draft", tab.getDisplayName());
        tab.setPath(Path.of("/tmp/script.py"));
        assertEquals("script.py", tab.getDisplayName());
        assertEquals("Python", tab.getLanguage().getName());
        // A null path falls back to Plain Text without throwing.
        tab.setPath(null);
        assertSame(Languages.PLAIN, tab.getLanguage());
        assertEquals("Draft", tab.getDisplayName());
    }

    @Test
    @DisplayName("replaceWholeText is a single undo step")
    void wholeTextReplace() throws BadLocationException {
        EditorTab tab = newTab();
        type(tab, "abc");
        tab.replaceWholeText("xyz");
        assertEquals("xyz", tab.getText());
        assertTrue(tab.canUndo());
        tab.undo();
        assertEquals("abc", tab.getText());
        tab.redo();
        assertEquals("xyz", tab.getText());
        // A no-op replace adds no undo step.
        int before = tab.canUndo() ? 1 : 0;
        tab.replaceWholeText("xyz");
        assertEquals(before, tab.canUndo() ? 1 : 0);
        // A null replacement empties the document.
        tab.replaceWholeText(null);
        assertEquals("", tab.getText());
    }

    @Test
    @DisplayName("charset and EOL setters validate their inputs")
    void metadataSetters() {
        EditorTab tab = newTab();
        tab.setCharset(StandardCharsets.ISO_8859_1);
        assertEquals(StandardCharsets.ISO_8859_1, tab.getCharset());
        tab.setCharset(null); // ignored
        assertEquals(StandardCharsets.ISO_8859_1, tab.getCharset());
        tab.setEol(TextFileIO.EOL_CRLF);
        assertEquals(TextFileIO.EOL_CRLF, tab.getEol());
        tab.setEol("\r"); // invalid: ignored
        assertEquals(TextFileIO.EOL_CRLF, tab.getEol());
        tab.setLanguage(null);
        assertSame(Languages.PLAIN, tab.getLanguage());
        tab.setLanguage(Languages.forName("Go"));
        assertEquals("Go", tab.getLanguage().getName());
        tab.setUntitledName(null); // ignored
        assertEquals("Untitled", tab.getDisplayName());
    }

    @Test
    @DisplayName("caret geometry follows the text")
    void caretGeometry() throws BadLocationException {
        EditorTab tab = newTab();
        type(tab, "one\ntwo\nthree");
        assertEquals(3, tab.getLineCount());
        tab.goToLine(2);
        assertEquals(2, tab.getCaretLine());
        assertEquals(1, tab.getCaretColumn());
        // Out-of-range lines clamp instead of throwing.
        tab.goToLine(99);
        assertEquals(3, tab.getCaretLine());
        tab.goToLine(-4);
        assertEquals(1, tab.getCaretLine());
        tab.textPane().setCaretPosition(5); // inside "two"
        assertEquals(2, tab.getCaretLine());
        assertEquals(2, tab.getCaretColumn());
    }

    @Test
    @DisplayName("applySettings restyles the tab and its theme")
    void applySettings() {
        EditorTab tab = newTab();
        EditorSettings dark = EditorSettings.defaults();
        dark.setThemeName("Dark");
        dark.setFontSize(20);
        dark.setLineNumbers(false);
        dark.setWordWrap(false);
        tab.applySettings(dark);
        assertSame(EditorTheme.DARK, tab.theme());
        assertEquals(EditorTheme.DARK.getBackground(),
                tab.textPane().getBackground());
        assertEquals(20, tab.textPane().getFont().getSize());
        // A null settings argument falls back to defaults, never throws.
        tab.applySettings(null);
        assertSame(EditorTheme.LIGHT, tab.theme());
    }

    @Test
    @DisplayName("the highlight gate skips plain and disabled languages")
    void highlightBehaviour() throws BadLocationException {
        EditorSettings noHighlight = EditorSettings.defaults();
        noHighlight.setHighlight(false);
        EditorTab tab = new EditorTab(noHighlight);
        type(tab, "public class X {}");
        // With highlighting off every character keeps the plain foreground.
        tab.applySettings(noHighlight);
        java.awt.Color plain = EditorTheme.LIGHT.getForeground();
        assertEquals(plain, javax.swing.text.StyleConstants.getForeground(
                tab.document().getCharacterElement(0).getAttributes()));
    }

    @Test
    @DisplayName("undo and redo with empty history are contained")
    void emptyUndoRedo() {
        EditorTab tab = newTab();
        tab.undo(); // must not throw
        tab.redo(); // must not throw
        tab.discardUndo();
        assertFalse(tab.canUndo());
    }
}
