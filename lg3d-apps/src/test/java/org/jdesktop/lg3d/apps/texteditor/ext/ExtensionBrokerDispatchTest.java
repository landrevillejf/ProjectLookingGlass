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
package org.jdesktop.lg3d.apps.texteditor.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.EditorStore;
import org.jdesktop.lg3d.apps.texteditor.TextEditorExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ExtensionBroker}'s shared document dispatch, focused
 * on the Phase-0 {@code onDocumentChanged} wiring: the debounced edit signal
 * fires for enabled READ extensions carrying the caret/selection geometry, the
 * WRITE permission gates the document/selection mutators, a disabled or READ-less
 * extension is skipped, and a throwing extension is isolated so the others still
 * run. Uses the package-private {@code ExtensionRegistry.seed} seam so no jar or
 * filesystem scanning is involved.
 */
class ExtensionBrokerDispatchTest {

    @TempDir
    Path storeDir;

    /** Records the last changed document; optionally throws on dispatch. */
    private static final class Recorder implements TextEditorExtension {
        private final String id;
        private final Set<TextEditorPermission> perms;
        private final boolean boom;
        DocumentContext last;
        int changedCalls;

        Recorder(String id, Set<TextEditorPermission> perms, boolean boom) {
            this.id = id;
            this.perms = perms;
            this.boom = boom;
        }

        @Override
        public TextEditorManifest manifest() {
            return new TextEditorManifest(id, id, "1.0", "", "test", perms);
        }

        @Override
        public void onDocumentChanged(DocumentContext doc) {
            changedCalls++;
            last = doc;
            if (boom) {
                throw new IllegalStateException("boom from " + id);
            }
        }
    }

    private ExtensionRegistry registry() {
        return new ExtensionRegistry(new EditorStore(storeDir));
    }

    private static ExtensionBroker broker(ExtensionRegistry registry) {
        return new ExtensionBroker(registry, EditorSinks.builder().build());
    }

    @Test
    @DisplayName("notifyDocumentChanged fires onDocumentChanged with caret geometry")
    void changedCarriesCaret() {
        ExtensionRegistry reg = registry();
        Recorder r = new Recorder("lg3d.rec", EnumSet.of(TextEditorPermission.READ), false);
        reg.seed(r, true, true, EnumSet.of(TextEditorPermission.READ));

        broker(reg).notifyDocumentChanged("/src/A.java", "A.java", "hello", "he",
                0, 1, 1, 0, 2, s -> { }, s -> { });

        assertEquals(1, r.changedCalls);
        assertEquals("/src/A.java", r.last.getFilePath());
        assertEquals(0, r.last.getCaretOffset());
        assertEquals(1, r.last.getCaretLine());
        assertEquals(1, r.last.getCaretColumn());
        assertEquals(2, r.last.getSelectionEnd());
    }

    @Test
    @DisplayName("an extension without READ is skipped on change")
    void readGateSkips() {
        ExtensionRegistry reg = registry();
        Recorder r = new Recorder("lg3d.noread",
                EnumSet.of(TextEditorPermission.WRITE), false);
        reg.seed(r, true, true, EnumSet.of(TextEditorPermission.WRITE));

        broker(reg).notifyDocumentChanged("/a", "a", "text", "",
                0, 1, 1, 0, 0, s -> { }, s -> { });

        assertEquals(0, r.changedCalls);
    }

    @Test
    @DisplayName("a disabled extension is skipped on change")
    void disabledSkips() {
        ExtensionRegistry reg = registry();
        Recorder r = new Recorder("lg3d.off", EnumSet.of(TextEditorPermission.READ), false);
        reg.seed(r, true, false, EnumSet.of(TextEditorPermission.READ));

        broker(reg).notifyDocumentChanged("/a", "a", "text", "",
                0, 1, 1, 0, 0, s -> { }, s -> { });

        assertEquals(0, r.changedCalls);
    }

    @Test
    @DisplayName("WRITE gates the mutators; a READ-only doc mutator is a no-op")
    void writeGatesMutators() {
        ExtensionRegistry reg = registry();
        Recorder rw = new Recorder("lg3d.rw",
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.WRITE), false);
        Recorder ro = new Recorder("lg3d.ro", EnumSet.of(TextEditorPermission.READ), false);
        reg.seed(rw, true, true,
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.WRITE));
        reg.seed(ro, true, true, EnumSet.of(TextEditorPermission.READ));

        final List<String> fullText = new ArrayList<>();
        final List<String> selection = new ArrayList<>();
        Consumer<String> textMutator = fullText::add;
        Consumer<String> selMutator = selection::add;

        // One dispatch hands each extension its own gated context.
        broker(reg).notifyDocumentChanged("/a", "a", "text", "",
                0, 1, 1, 0, 0, textMutator, selMutator);

        // WRITE extension: mutators reach the real sinks.
        rw.last.setFullText("NEW");
        rw.last.replaceSelection("SEL");
        assertEquals(List.of("NEW"), fullText);
        assertEquals(List.of("SEL"), selection);

        // READ-only extension: the broker wrapped the mutators as no-ops.
        ro.last.setFullText("IGNORED");
        ro.last.replaceSelection("IGNORED");
        assertEquals(List.of("NEW"), fullText);
        assertEquals(List.of("SEL"), selection);
    }

    @Test
    @DisplayName("a throwing extension is isolated; the rest still receive the hook")
    void exceptionIsolation() {
        ExtensionRegistry reg = registry();
        Recorder bad = new Recorder("lg3d.bad", EnumSet.of(TextEditorPermission.READ), true);
        Recorder good = new Recorder("lg3d.good", EnumSet.of(TextEditorPermission.READ), false);
        reg.seed(bad, true, true, EnumSet.of(TextEditorPermission.READ));
        reg.seed(good, true, true, EnumSet.of(TextEditorPermission.READ));

        broker(reg).notifyDocumentChanged("/a", "a", "text", "",
                0, 1, 1, 0, 0, s -> { }, s -> { });

        assertEquals(1, bad.changedCalls);
        assertEquals(1, good.changedCalls);
        assertTrue(good.last.getCaretLine() >= 1);
    }

    @Test
    @DisplayName("the legacy no-caret overload still dispatches with zero geometry")
    void legacyOpenedOverload() {
        final DocumentContext[] seen = new DocumentContext[1];
        TextEditorExtension ext = new TextEditorExtension() {
            @Override
            public TextEditorManifest manifest() {
                return new TextEditorManifest("lg3d.open", "open", "1.0", "", "test",
                        EnumSet.of(TextEditorPermission.READ));
            }

            @Override
            public void onDocumentOpened(DocumentContext doc) {
                seen[0] = doc;
            }
        };
        ExtensionRegistry reg = registry();
        reg.seed(ext, true, true, EnumSet.of(TextEditorPermission.READ));

        broker(reg).notifyDocumentOpened("/a", "a", "text", "", s -> { }, s -> { });

        assertTrue(seen[0] != null);
        assertEquals(0, seen[0].getCaretOffset());
        assertEquals(0, seen[0].getCaretLine());
    }

    @Test
    @DisplayName("contextFor grants sinks only to the permissions held (sanity)")
    void contextReflectsGrant() {
        ExtensionRegistry reg = registry();
        final List<String> reported = new ArrayList<>();
        TextEditorExtension ext = new TextEditorExtension() {
            @Override
            public TextEditorManifest manifest() {
                return new TextEditorManifest("lg3d.diag", "diag", "1.0", "", "test",
                        EnumSet.of(TextEditorPermission.DIAGNOSE));
            }

            @Override
            public void onEditorStarted(EditorContext ctx) {
                assertTrue(ctx.has(TextEditorPermission.DIAGNOSE));
                ctx.reportDiagnostics("/a",
                        List.of(Diagnostic.of("/a", 1, 1, Diagnostic.Kind.ERROR, "x")));
            }
        };
        reg.seed(ext, true, true, EnumSet.of(TextEditorPermission.DIAGNOSE));
        ExtensionBroker broker = new ExtensionBroker(reg, EditorSinks.builder()
                .reportDiagnostics((p, ds) -> reported.add(p))
                .build());
        broker.notifyStarted();
        assertEquals(List.of("/a"), reported);
    }
}
