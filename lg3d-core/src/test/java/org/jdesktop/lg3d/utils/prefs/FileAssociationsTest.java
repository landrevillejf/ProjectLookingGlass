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
package org.jdesktop.lg3d.utils.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.lg3d.utils.prefs.FileAssociations.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link FileAssociations}: the pure type-vocabulary helpers
 * (extension/MIME key normalisation, {@code %f} expansion, labels) and the
 * persisted association round-trip. Any association the round-trip tests touch
 * is snapshotted and restored in {@link #restore()}, so a developer's real
 * {@code .pdf} handler is never clobbered.
 */
class FileAssociationsTest {

    private final FileAssociations fa = FileAssociations.get();

    /** Original values of every type key a test mutates, for restoration. */
    private final Map<String, String> saved = new LinkedHashMap<>();

    /** Records the current handler for {@code key} before a test changes it. */
    private void touch(String key) {
        String canonical = FileAssociations.normalizeTypeKey(key);
        if (canonical != null && !saved.containsKey(canonical)) {
            saved.put(canonical, fa.handlerForType(canonical));
        }
    }

    @AfterEach
    void restore() {
        for (Map.Entry<String, String> e : saved.entrySet()) {
            if (e.getValue() == null) {
                fa.removeHandler(e.getKey());
            } else {
                fa.setHandler(e.getKey(), e.getValue());
            }
        }
        saved.clear();
    }

    // ------------------------------------------------------------------
    // bareExtension / keys / normalisation (pure)

    @Test
    void bareExtensionHandlesCommonShapes() {
        assertEquals("pdf", FileAssociations.bareExtension("report.pdf"));
        assertEquals("pdf", FileAssociations.bareExtension("REPORT.PDF"));
        assertEquals("gz", FileAssociations.bareExtension("archive.tar.gz"));
        assertEquals("pdf", FileAssociations.bareExtension("/home/me/dir/report.pdf"));
        assertEquals("pdf", FileAssociations.bareExtension("C:\\dir\\report.pdf"));
        // A dotfile, no dot, or a trailing dot has no extension.
        assertNull(FileAssociations.bareExtension(".bashrc"));
        assertNull(FileAssociations.bareExtension("Makefile"));
        assertNull(FileAssociations.bareExtension("file."));
        assertNull(FileAssociations.bareExtension(""));
        assertNull(FileAssociations.bareExtension(null));
    }

    @Test
    void extensionAndMimeKeys() {
        assertEquals("ext:pdf", FileAssociations.extensionKey("pdf"));
        assertEquals("ext:pdf", FileAssociations.extensionKey(".PDF"));
        assertNull(FileAssociations.extensionKey(null));
        assertNull(FileAssociations.extensionKey(""));
        assertEquals("mime:application/pdf", FileAssociations.mimeKey("Application/PDF"));
        assertNull(FileAssociations.mimeKey("  "));
    }

    @Test
    void normalizeTypeKeyAcceptsEverySpelling() {
        assertEquals("ext:pdf", FileAssociations.normalizeTypeKey("pdf"));
        assertEquals("ext:pdf", FileAssociations.normalizeTypeKey(".pdf"));
        assertEquals("ext:pdf", FileAssociations.normalizeTypeKey("EXT:PDF"));
        assertEquals("mime:application/pdf",
                FileAssociations.normalizeTypeKey("application/pdf"));
        assertEquals("mime:text/plain",
                FileAssociations.normalizeTypeKey("mime:text/plain"));
        assertNull(FileAssociations.normalizeTypeKey(""));
        assertNull(FileAssociations.normalizeTypeKey(null));
    }

    @Test
    void labelForResolvesCommonAndCustomTypes() {
        assertEquals("PDF document", FileAssociations.labelFor("ext:pdf"));
        assertEquals("zzz", FileAssociations.labelFor("ext:zzz"));
        assertEquals("application/pdf", FileAssociations.labelFor("mime:application/pdf"));
        assertEquals("", FileAssociations.labelFor(null));
    }

    // ------------------------------------------------------------------
    // expand (pure)

    @Test
    void expandSubstitutesTheFileToken() {
        Path p = Paths.get("/tmp/some file.pdf");
        List<String> argv = FileAssociations.expand("evince %f", p);
        assertEquals(List.of("evince", "/tmp/some file.pdf"), argv);
    }

    @Test
    void expandAppendsWhenNoToken() {
        Path p = Paths.get("/tmp/report.pdf");
        assertEquals(List.of("evince", "/tmp/report.pdf"),
                FileAssociations.expand("evince", p));
    }

    @Test
    void expandHandlesEmbeddedTokenAndMultipleTokens() {
        Path p = Paths.get("/tmp/a.pdf");
        assertEquals(List.of("tool", "--in=/tmp/a.pdf", "--x"),
                FileAssociations.expand("tool --in=%f --x", p));
        // Two tokens both carrying %f are each substituted.
        assertEquals(List.of("/tmp/a.pdf", "/tmp/a.pdf"),
                FileAssociations.expand("%f %f", p));
    }

    @Test
    void expandIsSafeForBlankInput() {
        assertTrue(FileAssociations.expand(null, Paths.get("/tmp/a")).isEmpty());
        assertTrue(FileAssociations.expand("   ", Paths.get("/tmp/a")).isEmpty());
    }

    // ------------------------------------------------------------------
    // common types

    @Test
    void commonTypesAreWellFormed() {
        List<Type> types = FileAssociations.commonTypes();
        assertFalse(types.isEmpty());
        Set<String> keys = new LinkedHashSet<>();
        Type pdf = null;
        for (Type t : types) {
            assertNotNull(t.key());
            assertNotNull(t.label());
            assertNotNull(t.extension());
            assertNotNull(t.mime());
            assertTrue(keys.add(t.key()), "duplicate type key " + t.key());
            if (t.extension().equals("pdf")) {
                pdf = t;
            }
        }
        assertNotNull(pdf, "pdf must be a curated type");
        assertEquals("ext:pdf", pdf.key());
        assertEquals("application/pdf", pdf.mime());
        assertEquals("PDF document", pdf.label());
    }

    @Test
    void mimeTypeFallsBackToExtensionMap() {
        // A path that does not exist cannot be probed, so the built-in
        // extension fallback supplies the MIME type.
        assertEquals("application/pdf",
                FileAssociations.mimeTypeOf(Paths.get("no-such-file-xyz.pdf")));
        assertNull(FileAssociations.mimeTypeOf(Paths.get("no-such-file-xyz")));
        assertNull(FileAssociations.mimeTypeOf(null));
    }

    // ------------------------------------------------------------------
    // persisted round-trip

    @Test
    void setAndGetRoundTrips(@TempDir Path dir) throws Exception {
        touch("ext:lg3dtest");
        fa.setHandler("ext:lg3dtest", "java org.example.Handler");
        assertEquals("java org.example.Handler", fa.handlerForType("lg3dtest"));
        assertEquals("java org.example.Handler", fa.handlerForType("ext:lg3dtest"));

        Path file = Files.createFile(dir.resolve("doc.lg3dtest"));
        assertEquals("java org.example.Handler", fa.handlerFor(file));

        assertTrue(fa.handlers().containsKey("ext:lg3dtest"));

        fa.removeHandler("lg3dtest");
        assertNull(fa.handlerForType("ext:lg3dtest"));
        assertNull(fa.handlerFor(file));
    }

    @Test
    void blankCommandRemovesTheAssociation() {
        touch("ext:lg3dblank");
        fa.setHandler("ext:lg3dblank", "foo");
        assertEquals("foo", fa.handlerForType("ext:lg3dblank"));
        fa.setHandler("ext:lg3dblank", "  ");
        assertNull(fa.handlerForType("ext:lg3dblank"));
    }

    @Test
    void extensionAssociationWinsOverMime(@TempDir Path dir) throws Exception {
        touch("ext:pdf");
        touch("mime:application/pdf");
        Path pdf = Files.createFile(dir.resolve("paper.pdf"));

        fa.setHandler("mime:application/pdf", "mime-handler");
        fa.setHandler("ext:pdf", "ext-handler");
        // The extension is consulted first, so it wins.
        assertEquals("ext-handler", fa.handlerFor(pdf));

        // With the extension association gone, the MIME association applies.
        fa.removeHandler("ext:pdf");
        assertEquals("mime-handler", fa.handlerFor(pdf));

        fa.removeHandler("mime:application/pdf");
        assertNull(fa.handlerFor(pdf));
    }

    @Test
    void unknownKeysAreIgnored() {
        assertNull(fa.handlerForType(null));
        assertNull(fa.handlerForType(""));
        // A no-op that must not throw.
        fa.setHandler(null, "x");
        fa.removeHandler("   ");
    }
}
