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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.FindReplaceBar.Host;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the editor's small Swing surfaces: the
 * {@link EditorStatusBar} labels, the {@link FindReplaceBar} accessors and
 * host callbacks, and the {@link RecentCard} / {@link ExtensionsCard} list
 * models. All are plain components that construct under
 * {@code java.awt.headless=true}.
 */
class EditorWidgetsTest {

    // ------------------------------------------------------------------
    // EditorStatusBar
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the status bar formats every slot")
    void statusBarFormats() {
        EditorStatusBar bar = new EditorStatusBar();
        bar.setPosition(12, 7);
        assertEquals("Ln 12, Col 7", bar.getPosition());

        bar.setSelection(0);
        assertEquals(" ", bar.getSelection());
        bar.setSelection(42);
        assertEquals("(42 selected)", bar.getSelection());

        bar.setDocumentSize(1, 1);
        assertEquals("1 chars, 1 line", bar.getDocumentSize());
        bar.setDocumentSize(300, 20);
        assertEquals("300 chars, 20 lines", bar.getDocumentSize());

        bar.setFileInfo("UTF-8", TextFileIO.EOL_CRLF, "Java");
        assertEquals("UTF-8 \u00B7 CRLF \u00B7 Java", bar.getFileInfo());
        bar.setFileInfo("UTF-8", TextFileIO.EOL_LF, "Plain Text");
        assertEquals("UTF-8 \u00B7 LF \u00B7 Plain Text", bar.getFileInfo());

        bar.setFontSize(16);
        assertEquals("16 pt", bar.getFontSize());

        assertEquals("", bar.getMessage());
        bar.setMessage("hello");
        assertEquals("hello", bar.getMessage());
        bar.setMessage(null); // blanks the slot
        assertEquals("", bar.getMessage());
        bar.setMessage("");
        assertEquals("", bar.getMessage());
    }

    // ------------------------------------------------------------------
    // FindReplaceBar
    // ------------------------------------------------------------------

    /** A {@link Host} that records which callback fired. */
    private static final class RecordingHost implements Host {

        final List<String> calls = new ArrayList<>();

        @Override
        public void queryChanged() {
            calls.add("queryChanged");
        }

        @Override
        public void findNext() {
            calls.add("findNext");
        }

        @Override
        public void findPrevious() {
            calls.add("findPrevious");
        }

        @Override
        public void replaceOne() {
            calls.add("replaceOne");
        }

        @Override
        public void replaceAll() {
            calls.add("replaceAll");
        }

        @Override
        public void goToLine() {
            calls.add("goToLine");
        }

        @Override
        public void closeBar() {
            calls.add("closeBar");
        }
    }

    @Test
    @DisplayName("the find bar defaults to literal, case-insensitive, wrapping")
    void findBarDefaults() {
        FindReplaceBar bar = new FindReplaceBar(new RecordingHost());
        assertEquals("", bar.query());
        assertEquals("", bar.replacement());
        assertFalse(bar.matchCase());
        assertFalse(bar.regex());
        assertTrue(bar.wrap());
        assertEquals(-1, bar.lineNumber());
        assertEquals("", bar.matchInfo());
    }

    @Test
    @DisplayName("typing a query notifies the host live")
    void findBarNotifies() {
        RecordingHost host = new RecordingHost();
        FindReplaceBar bar = new FindReplaceBar(host);
        bar.setQuery("abc");
        assertTrue(host.calls.contains("queryChanged"));
        assertEquals("abc", bar.query());
        bar.setReplacement("xyz");
        assertEquals("xyz", bar.replacement());
        bar.setMatchInfo("3 of 9");
        assertEquals("3 of 9", bar.matchInfo());
        bar.setMatchInfo(null); // tolerated
        assertEquals("", bar.matchInfo());
    }

    @Test
    @DisplayName("the line field parses ints through the setter")
    void lineNumberParsing() {
        FindReplaceBar bar = new FindReplaceBar(new RecordingHost());
        bar.setLineNumber(42);
        assertEquals(42, bar.lineNumber());
        bar.setLineNumber(7);
        assertEquals(7, bar.lineNumber());
    }

    // ------------------------------------------------------------------
    // RecentCard / ExtensionsCard
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the recent card loads, selects and reports its entries")
    void recentCard() {
        final List<String> opened = new ArrayList<>();
        RecentCard card = new RecentCard(new RecentCard.Host() {
            @Override
            public void openRecent(String path) {
                opened.add(path);
            }

            @Override
            public void removeRecent(String path) {
            }

            @Override
            public void clearRecent() {
            }

            @Override
            public void closeCard() {
            }
        });
        assertEquals(0, card.entryCount());
        card.load(List.of("/tmp/a.txt", "/tmp/b.txt"));
        assertEquals(2, card.entryCount());
        card.load(null); // tolerated
        assertEquals(0, card.entryCount());
        card.load(List.of("/tmp/a.txt"));
        assertTrue(opened.isEmpty(), "loading must not open anything");
    }

    @Test
    @DisplayName("the extensions card lists labels and enables Run on select")
    void extensionsCard() {
        final List<Integer> runs = new ArrayList<>();
        ExtensionsCard card = new ExtensionsCard(new ExtensionsCard.Host() {
            @Override
            public void runExtensionAction(int index) {
                runs.add(index);
            }

            @Override
            public void closeCard() {
            }
        });
        card.load(List.of("Text Tools: Sort Lines (A-Z)",
                "Text Tools: Insert Timestamp"));
        assertEquals(2, card.entryCount());
        card.select(1);
        card.select(-1); // deselect tolerated
        card.load(null);
        assertEquals(0, card.entryCount());
        assertTrue(runs.isEmpty(), "selecting must not run anything");
    }

    @Test
    @DisplayName("the extensions card groups rows under inert category headers")
    void extensionsCardGrouped() {
        final List<Integer> runs = new ArrayList<>();
        ExtensionsCard card = new ExtensionsCard(new ExtensionsCard.Host() {
            @Override
            public void runExtensionAction(int index) {
                runs.add(index);
            }

            @Override
            public void closeCard() {
            }
        });
        card.loadRows(List.of(
                ExtensionsCard.Row.header("Text"),
                ExtensionsCard.Row.action("Text Tools: Sort Lines (A-Z)", 0),
                ExtensionsCard.Row.header("Code"),
                ExtensionsCard.Row.action("Code Tools: Indent Lines", 7)));
        // headers are not counted as runnable entries
        assertEquals(2, card.entryCount());
        card.select(0); // header - Run must stay disabled
        card.select(1); // an action row
        assertTrue(runs.isEmpty(), "selecting must not run anything");
    }
}
