package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link DeckCodecs}, the one facade both desktops call: it sniffs a
 * file's real format from its magic bytes (not its extension), routes an open to
 * the matching codec, and dispatches an export to the right writer. Every case is
 * headless - the decks are built in-test, the {@code .pptx}/{@code .ppt} through
 * Apache POI exactly as a real office would write them.
 */
class DeckCodecsTest {

    // ------------------------------------------------------------------
    // Sniffing by content
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sniff detects each format from content, whatever the extension says")
    void sniffsEveryFormat(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFFFF0000, 4, 4);
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            File zip = new File(dir, "deck.zip");
            File pptx = new File(dir, "deck.pptx");
            File ppt = new File(dir, "deck.ppt");
            File odp = new File(dir, "deck.odp");
            DeckCodecs.export(model, zip, DeckFormat.NATIVE);
            DeckCodecs.export(model, pptx, DeckFormat.PPTX);
            DeckCodecs.export(model, ppt, DeckFormat.PPT);
            DeckCodecs.export(model, odp, DeckFormat.ODP);

            assertEquals(DeckFormat.NATIVE, DeckCodecs.sniff(zip));
            assertEquals(DeckFormat.PPTX, DeckCodecs.sniff(pptx));
            assertEquals(DeckFormat.PPT, DeckCodecs.sniff(ppt));
            assertEquals(DeckFormat.ODP, DeckCodecs.sniff(odp));
        }
    }

    @Test
    @DisplayName("content beats the extension: a real .pptx named .zip is still PPTX")
    void sniffedContentOverridesExtension(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF00FF00, 3, 3);
        File misnamed = new File(dir, "really-a-pptx.zip");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, misnamed, DeckFormat.PPTX);
        }
        assertEquals(DeckFormat.PPTX, DeckCodecs.sniff(misnamed),
                "the ZIP contents name it, not the .zip suffix");
    }

    @Test
    @DisplayName("Markdown has no magic bytes, so sniff knows it by its extension")
    void sniffsMarkdownByExtension(@TempDir File dir) throws IOException {
        File md = new File(dir, "deck.md");
        Files.writeString(md.toPath(), "# Title\n\nsome body text\n", StandardCharsets.UTF_8);
        assertEquals(DeckFormat.MARKDOWN, DeckCodecs.sniff(md),
                "plain text is neither OLE2 nor ZIP, so the extension names it");
        File markdown = new File(dir, "deck.markdown");
        Files.writeString(markdown.toPath(), "# Title\n", StandardCharsets.UTF_8);
        assertEquals(DeckFormat.MARKDOWN, DeckCodecs.sniff(markdown));
    }

    @Test
    @DisplayName("an unrecognised ZIP falls back to its extension, then open still fails")
    void unknownZipFallsBackToExtension(@TempDir File dir) throws IOException {
        File fake = new File(dir, "fake.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(fake.toPath()))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("not a deck".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        // No content.xml / mimetype / ppt entry, so sniffing falls back to ".zip".
        assertEquals(DeckFormat.NATIVE, DeckCodecs.sniff(fake));
        // But opening it as a native deck fails: there is no content.xml.
        assertThrows(IOException.class, () -> DeckCodecs.open(fake));
    }

    @Test
    @DisplayName("a file that is neither OLE2, ZIP nor a known extension is rejected")
    void rejectsNonDeck(@TempDir File dir) throws IOException {
        File notes = new File(dir, "notes.txt");
        Files.writeString(notes.toPath(), "just some plain text, not a deck");
        assertThrows(IOException.class, () -> DeckCodecs.sniff(notes));
        assertThrows(IOException.class, () -> DeckCodecs.open(notes));
    }

    // ------------------------------------------------------------------
    // formatFor (the Save As default)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formatFor maps a known extension and defaults to NATIVE otherwise")
    void formatForDefaultsToNative() {
        assertEquals(DeckFormat.PPTX, DeckCodecs.formatFor(new File("a.pptx")));
        assertEquals(DeckFormat.PPT, DeckCodecs.formatFor(new File("a.ppt")));
        assertEquals(DeckFormat.ODP, DeckCodecs.formatFor(new File("a.odp")));
        assertEquals(DeckFormat.MARKDOWN, DeckCodecs.formatFor(new File("a.md")));
        assertEquals(DeckFormat.MARKDOWN, DeckCodecs.formatFor(new File("a.markdown")),
                "the .markdown alias resolves to the same format");
        assertEquals(DeckFormat.NATIVE, DeckCodecs.formatFor(new File("a.zip")));
        assertEquals(DeckFormat.NATIVE, DeckCodecs.formatFor(new File("a.unknown")),
                "an unknown extension defaults to Presentoire's own format");
        assertEquals(DeckFormat.NATIVE, DeckCodecs.formatFor(new File("noextension")));
    }

    // ------------------------------------------------------------------
    // DeckFormat value type
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DeckFormat exposes its extension, description and capabilities")
    void deckFormatMetadata() {
        assertEquals("zip", DeckFormat.NATIVE.extension());
        assertEquals("pptx", DeckFormat.PPTX.extension());
        assertEquals("ppt", DeckFormat.PPT.extension());
        assertEquals("odp", DeckFormat.ODP.extension());
        assertEquals("md", DeckFormat.MARKDOWN.extension());
        assertTrue(DeckFormat.PPTX.description().contains("PowerPoint"));
        assertTrue(DeckFormat.MARKDOWN.description().contains("Markdown"));
        for (DeckFormat format : DeckFormat.values()) {
            assertTrue(format.canImport(), format + " imports");
            assertTrue(format.canExport(), format + " exports");
        }
    }

    @Test
    @DisplayName("DeckFormat.fromExtension is case-insensitive and null-safe")
    void fromExtension() {
        assertEquals(DeckFormat.PPTX, DeckFormat.fromExtension("Deck.PPTX"));
        assertEquals(DeckFormat.ODP, DeckFormat.fromExtension("/some/path/deck.odp"));
        assertEquals(DeckFormat.MARKDOWN, DeckFormat.fromExtension("deck.md"));
        assertEquals(DeckFormat.MARKDOWN, DeckFormat.fromExtension("deck.markdown"),
                "the .markdown alias resolves to MARKDOWN");
        assertEquals(DeckFormat.MARKDOWN, DeckFormat.fromExtension("/p/DECK.MD"),
                "case-insensitive, path stripped");
        assertNull(DeckFormat.fromExtension("deck.xyz"));
        assertNull(DeckFormat.fromExtension("noextension"));
        assertNull(DeckFormat.fromExtension("trailingdot."));
        assertNull(DeckFormat.fromExtension(null));
    }

    // ------------------------------------------------------------------
    // Facade open / export round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("open(File) sniffs and reads back an exported .odp deck")
    void openRoutesThroughSniffer(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF0000FF, 4, 4);
        File odp = new File(dir, "round.odp");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, odp, DeckFormat.ODP);
        }
        try (PresentoireModel read = DeckCodecs.open(odp)) {
            assertEquals(1, read.getSlideCount());
            assertTrue(read.getSlide(0).elements().size() >= 2,
                    "the text and list survive the round trip");
        }
    }

    @Test
    @DisplayName("open(File, format) forces a codec and export writes a re-openable file")
    void forcedFormatRoundTrips(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFFFF0000, 4, 4);
        File pptx = new File(dir, "forced.pptx");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, pptx, DeckFormat.PPTX);
        }
        assertTrue(pptx.length() > 0, "the export produced a file");
        try (PresentoireModel read = DeckCodecs.open(pptx, DeckFormat.PPTX)) {
            assertNotNull(read);
            assertEquals(1, read.getSlideCount());
        }
    }

    @Test
    @DisplayName("export to .md then open(File) sniffs it back through the facade")
    void markdownFacadeRoundTrips(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF0000FF, 4, 4);
        File md = new File(dir, "facade.md");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, md, DeckFormat.MARKDOWN);
        }
        assertEquals(DeckFormat.MARKDOWN, DeckCodecs.sniff(md));
        try (PresentoireModel read = DeckCodecs.open(md)) {
            assertEquals(1, read.getSlideCount());
            assertTrue(read.getSlide(0).elements().size() >= 2,
                    "the title and list survive the facade round trip");
        }
    }

    @Test
    @DisplayName("openMarkdown routes a multi-file set to the codec and rejects a non-.md")
    void openMarkdownRoutesMultiFile(@TempDir File dir) throws IOException {
        File a = new File(dir, "a.md");
        File b = new File(dir, "b.md");
        Files.writeString(a.toPath(), "# Alpha\n", StandardCharsets.UTF_8);
        Files.writeString(b.toPath(), "# Beta\n", StandardCharsets.UTF_8);
        try (PresentoireModel model = DeckCodecs.openMarkdown(new File[] {a, b})) {
            assertEquals(2, model.getSlideCount(), "one slide per file");
        }
        File txt = new File(dir, "notes.txt");
        Files.writeString(txt.toPath(), "not markdown", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> DeckCodecs.openMarkdown(new File[] {a, txt}),
                "a mixed set is refused");
    }
}
