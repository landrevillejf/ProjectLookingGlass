/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link SnippetsTools}: the pure value producers evaluated
 * from a fixed {@link Instant}, the {@code ${name}} completion-token filter, and
 * the SPI wiring that inserts a value at the caret (WRITE) and publishes snippet
 * tokens on an identifier prefix (READ). No panel or popup is constructed.
 */
class SnippetsToolsTest {

    private static final Instant T = Instant.ofEpochSecond(1_700_000_000L);

    private static final String UUID_RE =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    @DisplayName("expand evaluates every snippet deterministically from an instant")
    void expandValues() {
        assertEquals("1700000000", SnippetsTools.expand("epoch", T));
        assertEquals("2023-11-14T22:13:20Z", SnippetsTools.expand("iso", T));
        assertEquals("2023-11-14", SnippetsTools.expand("date", T));
        assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", SnippetsTools.expand("rfc", T));
        assertTrue(SnippetsTools.expand("lorem", T).startsWith("Lorem ipsum"));
        assertTrue(SnippetsTools.expand("color", T).matches("#[0-9A-F]{6}"));
        assertTrue(SnippetsTools.expand("uuid", T).matches(UUID_RE));
        assertTrue(SnippetsTools.expand("colour", T).matches("#[0-9A-F]{6}"),
                "the British spelling is an accepted alias");
    }

    @Test
    @DisplayName("unknown and null snippet names expand to nothing")
    void expandSafe() {
        assertEquals("", SnippetsTools.expand("bogus", T));
        assertEquals("", SnippetsTools.expand(null, T));
    }

    @Test
    @DisplayName("matching filters the ${name} tokens by the identifier prefix")
    void tokenFilter() {
        assertEquals(List.of("${uuid}"), SnippetsTools.matching("u"));
        assertEquals(List.of("${date}"), SnippetsTools.matching("da"));
        assertEquals(List.of("${color}"), SnippetsTools.matching("c"));
        assertEquals(List.of("${uuid}"), SnippetsTools.matching("uu"), "'uuid' starts with 'uu'");
        assertTrue(SnippetsTools.matching("zz").isEmpty(), "no name starts with 'zz'");
        assertTrue(SnippetsTools.matching("").isEmpty());
        assertTrue(SnippetsTools.matching(null).isEmpty());
        assertEquals(7, SnippetsTools.tokens().size());
        assertTrue(SnippetsTools.tokens().containsAll(
                List.of("${uuid}", "${date}", "${iso}", "${rfc}",
                        "${epoch}", "${lorem}", "${color}")));
    }

    @Test
    @DisplayName("the manifest is Insert category with READ + WRITE + TOOLBAR")
    void manifest() {
        SnippetsTools ext = new SnippetsTools();
        assertEquals("lg3d.snippets", ext.manifest().getId());
        assertEquals("Insert", ext.category());
        Set<TextEditorPermission> perms = ext.manifest().getPermissions();
        assertTrue(perms.containsAll(EnumSet.of(TextEditorPermission.READ,
                TextEditorPermission.WRITE, TextEditorPermission.TOOLBAR)));
    }

    @Test
    @DisplayName("every toolbar contribution carries an icon glyph")
    void contributionsHaveIcons() {
        SnippetsTools ext = new SnippetsTools();
        List<ToolbarContribution> cs = ext.toolbarContributions();
        assertEquals(5, cs.size());
        for (ToolbarContribution c : cs) {
            assertNotNull(c.getIcon(), c.getId() + " should contribute art");
        }
    }

    @Test
    @DisplayName("a toolbar action inserts the expanded value at the caret")
    void actionInserts() {
        SnippetsTools ext = new SnippetsTools();
        List<String> inserted = new ArrayList<>();
        Consumer<String> sel = inserted::add;
        DocumentContext doc = new DocumentContext("/a.txt", "a.txt", "", "",
                0, 1, 1, 0, 0, s -> { }, sel);
        ext.onDocumentOpened(doc);
        ext.toolbarContributions().get(0).getAction().run(); // uuid
        assertEquals(1, inserted.size());
        assertTrue(inserted.get(0).matches(UUID_RE), inserted.get(0));
    }

    // -- publish path (READ) ----------------------------------------------------

    private static final class Harness {
        final List<List<String>> publishes = new ArrayList<>();

        EditorContext ctx() {
            EditorSinks sinks = EditorSinks.builder()
                    .showCompletions((p, list) -> publishes.add(list))
                    .build();
            return new EditorContext(
                    EnumSet.of(TextEditorPermission.READ, TextEditorPermission.WRITE), sinks);
        }
    }

    @Test
    @DisplayName("typing a snippet-name prefix publishes its ${token}")
    void publishOnPrefix() {
        Harness h = new Harness();
        SnippetsTools ext = new SnippetsTools();
        ext.onEditorStarted(h.ctx());
        String text = "x = z";
        ext.onDocumentChanged(new DocumentContext("/a.txt", "a.txt", text, "",
                text.length(), 1, 1, text.length(), text.length(), s -> { }, s -> { }));
        assertFalse(h.publishes.isEmpty());
        List<String> last = h.publishes.get(h.publishes.size() - 1);
        assertTrue(last.isEmpty(), "'z' matches no snippet name: " + last);

        String text2 = "x = u";
        ext.onDocumentChanged(new DocumentContext("/a.txt", "a.txt", text2, "",
                text2.length(), 1, 1, text2.length(), text2.length(), s -> { }, s -> { }));
        last = h.publishes.get(h.publishes.size() - 1);
        assertEquals(List.of("${uuid}"), last);
    }
}
