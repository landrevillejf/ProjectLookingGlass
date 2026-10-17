package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers Presentoire's own {@code .zip} format through {@link DeckCodecs}: the
 * new writer serialises a model back to the very {@code content.xml} vocabulary
 * the shared parser reads, copies every referenced picture into the archive, and
 * a write-then-read round trip preserves slides, elements, geometry, colour (via
 * {@link ModelColor#toHex(int)}) and image pixels. Headless throughout.
 */
class NativeDeckCodecTest {

    @Test
    @DisplayName("write then read preserves slides, elements, geometry and colour")
    void roundTripsTheWholeVocabulary(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFFFF0000, 3, 3);
        File deck = new File(dir, "native.zip");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, deck, DeckFormat.NATIVE);
        }

        try (PresentoireModel read = DeckCodecs.open(deck)) {
            assertTrue(read.hasArchive(), "a native deck is a real ZIP");
            assertEquals(1, read.getSlideCount());
            SlideModel slide = read.getSlide(0);
            assertEquals("s1", slide.id());
            assertEquals(3, slide.elements().size(), "text, list and image all survive");

            SlideElement.Text text = (SlideElement.Text) slide.elements().get(0);
            assertEquals(DeckFixtures.TITLE_TEXT, text.text());
            assertEquals(DeckFixtures.TITLE_ARGB, text.argb(), "the colour round-trips");
            assertEquals(-0.08f, text.x(), 1e-4f);
            assertEquals(0.03f, text.y(), 1e-4f);
            assertEquals(0.16f, text.width(), 1e-4f);
            assertEquals(0.03f, text.height(), 1e-4f);

            SlideElement.ListElement list = (SlideElement.ListElement) slide.elements().get(1);
            assertEquals(DeckFixtures.BULLETS, list.items());
            assertEquals(ModelColor.BLACK, list.argb());

            SlideElement.Image image = (SlideElement.Image) slide.elements().get(2);
            assertEquals("img/red.png", image.src());
            BufferedImage loaded = read.loadImage("img/red.png");
            assertNotNull(loaded, "the picture was copied into the archive");
            assertEquals(0xFFFF0000, loaded.getRGB(0, 0), "and its pixels survive losslessly");
        }
    }

    @Test
    @DisplayName("the archive carries content.xml first, then each referenced image once")
    void archiveLayout(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF00FF00, 2, 2);
        File deck = new File(dir, "layout.zip");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, deck, DeckFormat.NATIVE);
        }

        // content.xml is the very first entry, so the sniffer can rely on it.
        try (ZipInputStream zin = new ZipInputStream(
                java.nio.file.Files.newInputStream(deck.toPath()))) {
            ZipEntry first = zin.getNextEntry();
            assertNotNull(first);
            assertEquals(PresentoireModel.CONTENT_ENTRY, first.getName());
        }
        try (ZipFile zip = new ZipFile(deck)) {
            assertNotNull(zip.getEntry(PresentoireModel.CONTENT_ENTRY));
            assertNotNull(zip.getEntry("img/red.png"), "the image rides under its src path");
            String content = new String(
                    zip.getInputStream(zip.getEntry(PresentoireModel.CONTENT_ENTRY)).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(content.contains("<document>"), "a <document> root the sniffer recognises");
            assertTrue(content.contains("<slide"), "and the slide element");
            assertTrue(content.contains("Hello Presentoire"), "carrying the text");
        }
    }

    @Test
    @DisplayName("special characters and newlines in text survive XML escaping")
    void escapesAndUnescapesText(@TempDir File dir) throws IOException {
        String raw = "a < b & c > d \"quoted\" 'single'\nsecond line";
        SlideElement.Text text = new SlideElement.Text(
                -0.08f, 0f, SlideElement.DEFAULT_Z, 0.16f, 0.03f, ModelColor.BLACK, raw);
        SlideModel slide = new SlideModel("esc", "", List.of(text));
        File deck = new File(dir, "escaped.zip");
        try (PresentoireModel model = PresentoireModel.of(List.of(slide), null, null)) {
            DeckCodecs.export(model, deck, DeckFormat.NATIVE);
        }
        try (PresentoireModel read = DeckCodecs.open(deck)) {
            SlideElement.Text back = (SlideElement.Text) read.getSlide(0).elements().get(0);
            assertEquals(raw, back.text(), "escaping is transparent to the round trip");
        }
    }

    @Test
    @DisplayName("a translucent colour keeps its alpha byte through toHex/parse")
    void translucentColourRoundTrips(@TempDir File dir) throws IOException {
        int argb = 0x80123456;
        SlideElement.Text text = new SlideElement.Text(
                -0.05f, 0f, SlideElement.DEFAULT_Z, 0.1f, 0.02f, argb, "translucent");
        SlideModel slide = new SlideModel("alpha", "", List.of(text));
        File deck = new File(dir, "alpha.zip");
        try (PresentoireModel model = PresentoireModel.of(List.of(slide), null, null)) {
            DeckCodecs.export(model, deck, DeckFormat.NATIVE);
        }
        try (PresentoireModel read = DeckCodecs.open(deck)) {
            SlideElement.Text back = (SlideElement.Text) read.getSlide(0).elements().get(0);
            assertEquals(argb, back.argb(), "the #RRGGBBAA form carries the alpha");
        }
    }

    @Test
    @DisplayName("a slide with no elements and a missing image both write and read cleanly")
    void emptySlideAndMissingImage(@TempDir File dir) throws IOException {
        SlideElement.Image absent = new SlideElement.Image(
                0f, 0f, SlideElement.DEFAULT_Z, 0.05f, 0.05f, "img/absent.png");
        SlideModel empty = new SlideModel("empty", "", List.of());
        SlideModel withMissing = new SlideModel("missing", "", List.of(absent));
        File deck = new File(dir, "sparse.zip");
        // No inline image for "img/absent.png", so the writer simply omits it.
        try (PresentoireModel model = PresentoireModel.of(
                List.of(empty, withMissing), null, new LinkedHashMap<>())) {
            DeckCodecs.export(model, deck, DeckFormat.NATIVE);
        }
        try (PresentoireModel read = DeckCodecs.open(deck)) {
            assertEquals(2, read.getSlideCount());
            assertEquals(0, read.getSlide(0).elements().size());
            assertEquals(1, read.getSlide(1).elements().size());
            assertEquals("img/absent.png",
                    ((SlideElement.Image) read.getSlide(1).elements().get(0)).src());
            assertNull(read.loadImage("img/absent.png"),
                    "an image that was never stored resolves to null, not a throw");
        }
    }

    @Test
    @DisplayName("a multi-slide deck keeps its order and per-slide transitions")
    void multiSlideOrderAndTransitions(@TempDir File dir) throws IOException {
        SlideModel a = new SlideModel("a", "rotation", List.of(
                new SlideElement.Text(-0.05f, 0f, SlideElement.DEFAULT_Z, 0.1f, 0.02f,
                        ModelColor.BLACK, "A")));
        SlideModel b = new SlideModel("b", "", List.of(
                new SlideElement.Text(-0.05f, 0f, SlideElement.DEFAULT_Z, 0.1f, 0.02f,
                        ModelColor.BLACK, "B")));
        File deck = new File(dir, "multi.zip");
        try (PresentoireModel model = PresentoireModel.of(List.of(a, b), null, null)) {
            DeckCodecs.export(model, deck, DeckFormat.NATIVE);
        }
        try (PresentoireModel read = DeckCodecs.open(deck)) {
            assertEquals(List.of("a", "b"), read.getSlideIds());
            assertTrue(read.getSlide(0).hasTransition());
            assertEquals("rotation", read.getSlide(0).transition());
            assertFalse(read.getSlide(1).hasTransition(), "a slide with none declares none");
        }
    }
}
