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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the bundled {@link BuiltinTextTools} extension (pure
 * text transforms plus the SPI install contract) and for the fault-isolating
 * {@link ExtensionLoader}.
 */
class BuiltinTextToolsTest {

    /** A recording {@link EditorContext} stand-in. */
    private static final class RecordingContext implements EditorContext {

        String text = "";
        String selection = "";
        String lastMessage;
        final List<String> actions = new ArrayList<>();
        final List<Runnable> runnables = new ArrayList<>();

        @Override
        public String documentText() {
            return text;
        }

        @Override
        public void setDocumentText(String newText) {
            this.text = newText;
        }

        @Override
        public String selectedText() {
            return selection;
        }

        @Override
        public void replaceSelection(String newText) {
            text = text.replace(selection, newText);
            selection = "";
        }

        @Override
        public String currentFileName() {
            return null;
        }

        @Override
        public void showMessage(String message) {
            lastMessage = message;
        }

        @Override
        public void addAction(String label, Runnable action) {
            actions.add(label);
            runnables.add(action);
        }
    }

    @Test
    @DisplayName("sortLines sorts case-insensitively and keeps the trailing newline")
    void sortLines() {
        assertEquals("a\nB\nc", BuiltinTextTools.sortLines("c\na\nB", false));
        assertEquals("c\nB\na", BuiltinTextTools.sortLines("c\na\nB", true));
        assertEquals("a\nb\n", BuiltinTextTools.sortLines("b\na\n", false));
        assertEquals("", BuiltinTextTools.sortLines("", false));
        assertEquals(null, BuiltinTextTools.sortLines(null, false));
    }

    @Test
    @DisplayName("stripTrailingWhitespace cleans spaces and tabs per line")
    void stripTrailingWhitespace() {
        assertEquals("a\nb\tc\n",
                BuiltinTextTools.stripTrailingWhitespace("a  \nb\tc\n"));
        assertEquals("x", BuiltinTextTools.stripTrailingWhitespace("x\t "));
        assertEquals("", BuiltinTextTools.stripTrailingWhitespace(""));
        assertEquals(null, BuiltinTextTools.stripTrailingWhitespace(null));
    }

    @Test
    @DisplayName("timestamp matches the documented format")
    void timestamp() {
        assertTrue(BuiltinTextTools.timestamp()
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    @DisplayName("install registers the six Text Tools actions")
    void installRegistersActions() {
        RecordingContext context = new RecordingContext();
        BuiltinTextTools tools = new BuiltinTextTools();
        assertEquals(BuiltinTextTools.NAME, tools.name());
        tools.install(context);
        assertEquals(6, context.actions.size());
        assertEquals("Sort Lines (A-Z)", context.actions.get(0));
        assertEquals("Sort Lines (Z-A)", context.actions.get(1));
        assertEquals("Remove Trailing Whitespace", context.actions.get(2));
        assertEquals("Insert Timestamp", context.actions.get(3));
        assertEquals("UPPERCASE Selection", context.actions.get(4));
        assertEquals("lowercase Selection", context.actions.get(5));
    }

    @Test
    @DisplayName("the registered actions transform the document")
    void actionsWork() {
        RecordingContext context = new RecordingContext();
        new BuiltinTextTools().install(context);
        context.text = "b\na";
        context.runnables.get(0).run(); // sort A-Z
        assertEquals("a\nb", context.text);
        assertEquals("Lines sorted A-Z", context.lastMessage);

        context.runnables.get(0).run(); // no change now
        assertEquals("Lines sorted A-Z (no change)", context.lastMessage);

        context.text = "a  \nb";
        context.runnables.get(2).run(); // strip trailing whitespace
        assertEquals("a\nb", context.text);

        context.text = "stamp here";
        context.selection = "here";
        context.runnables.get(4).run(); // UPPERCASE selection
        assertEquals("stamp HERE", context.text);

        context.text = "stamp HERE";
        context.selection = "HERE";
        context.runnables.get(5).run(); // lowercase selection
        assertEquals("stamp here", context.text);

        // Case transforms with no selection are silent no-ops.
        context.selection = "";
        String before = context.text;
        context.runnables.get(4).run();
        assertEquals(before, context.text);
    }

    @Test
    @DisplayName("discovery finds the bundled extension through META-INF/services")
    void discoverFindsBundled() {
        List<TextEditorExtension> found = ExtensionLoader.discover();
        assertTrue(found.stream()
                .anyMatch(e -> e instanceof BuiltinTextTools),
                "the bundled Text Tools must be on the service path");
    }

    @Test
    @DisplayName("installAll isolates a throwing extension")
    void installAllContainment() {
        RecordingContext context = new RecordingContext();
        TextEditorExtension boom = new TextEditorExtension() {
            @Override
            public String name() {
                return "Boom";
            }

            @Override
            public void install(EditorContext ctx) {
                throw new IllegalStateException("extension crash");
            }
        };
        TextEditorExtension good = new BuiltinTextTools();
        int installed = ExtensionLoader.installAll(List.of(boom, good),
                context);
        assertEquals(1, installed, "only the healthy extension installs");
        assertEquals(6, context.actions.size());

        // Null arguments and null entries are tolerated.
        assertEquals(0, ExtensionLoader.installAll(null, context));
        assertEquals(0, ExtensionLoader.installAll(List.of(good), null));
        List<TextEditorExtension> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(good);
        assertEquals(1, ExtensionLoader.installAll(withNull,
                new RecordingContext()));
    }

    @Test
    @DisplayName("discovery never returns null")
    void discoverNeverNull() {
        assertFalse(ExtensionLoader.discover() == null);
    }
}
