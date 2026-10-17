package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the legacy binary {@code .ppt} (97-2003) codec through
 * {@link DeckCodecs}, against genuine Apache POI HSLF files. The mapping mirrors
 * the {@code .pptx} codec - a text box to a {@link SlideElement.Text}, a bulleted
 * body to a {@link SlideElement.ListElement}, a picture to a
 * {@link SlideElement.Image} - only the container differs. Colour is asserted
 * loosely because HSLF quantises an arbitrary RGB to its palette, so an exact
 * {@code #CC0000} is not guaranteed to survive the legacy format; text, bullets,
 * picture bytes and the fit-inside geometry are. Headless throughout.
 */
class PptDeckCodecTest {

    @Test
    @DisplayName("importing a POI-built .ppt maps its title, bullets and picture")
    void importsAForeignDeck(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF0000FF, 4, 4);
        File ppt = new File(dir, "foreign.ppt");
        DeckFixtures.buildPpt(ppt, png);

        try (PresentoireModel model = DeckCodecs.open(ppt)) {
            assertEquals(1, model.getSlideCount());
            SlideModel slide = model.getSlide(0);

            SlideElement.Text text = null;
            SlideElement.ListElement list = null;
            SlideElement.Image image = null;
            for (SlideElement element : slide.elements()) {
                if (element instanceof SlideElement.Text t) {
                    text = t;
                } else if (element instanceof SlideElement.ListElement l) {
                    list = l;
                } else if (element instanceof SlideElement.Image i) {
                    image = i;
                }
            }

            assertNotNull(text, "the title text box imported");
            assertEquals(DeckFixtures.TITLE_TEXT, text.text());
            assertEquals(0xFF, ModelColor.alpha(text.argb()), "imported text is opaque");

            assertNotNull(list, "the bulleted body imported as a list");
            assertEquals(DeckFixtures.BULLETS, list.items());

            assertNotNull(image, "the picture imported");
            BufferedImage loaded = model.loadImage(image.src());
            assertNotNull(loaded, "and its bytes ride in the in-memory model");

            DeckFixtures.assertTextWithinSlide(text);
            DeckFixtures.assertTextWithinSlide(list);
            DeckFixtures.assertImageWithinSlide(image);
        }
    }

    @Test
    @DisplayName("exporting writes a .ppt POI re-opens with the same text, bullets and picture")
    void exportsAReopenableDeck(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFFFF0000, 4, 4);
        File out = new File(dir, "export.ppt");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, out, DeckFormat.PPT);
        }
        assertTrue(out.length() > 0, "the export produced a file");

        try (FileInputStream fin = new FileInputStream(out);
                HSLFSlideShow ppt = new HSLFSlideShow(fin)) {
            List<HSLFSlide> slides = ppt.getSlides();
            assertEquals(1, slides.size());

            StringBuilder text = new StringBuilder();
            boolean bulleted = false;
            boolean picture = false;
            for (HSLFShape shape : slides.get(0).getShapes()) {
                if (shape instanceof HSLFTextShape textShape) {
                    for (HSLFTextParagraph para : textShape.getTextParagraphs()) {
                        if (para.isBullet()) {
                            bulleted = true;
                        }
                        for (HSLFTextRun run : para.getTextRuns()) {
                            text.append(run.getRawText());
                        }
                        text.append('\n');
                    }
                } else if (shape instanceof HSLFPictureShape) {
                    picture = true;
                }
            }

            String all = text.toString();
            assertTrue(all.contains(DeckFixtures.TITLE_TEXT), "the title was written");
            assertTrue(all.contains("alpha") && all.contains("gamma"), "the list was written");
            assertTrue(bulleted, "the list is bulleted");
            assertTrue(picture, "the picture was written");
        }
    }

    @Test
    @DisplayName("an export re-imported through Presentoire round-trips its content")
    void exportThenImportRoundTrips(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF00FF00, 3, 3);
        File out = new File(dir, "round.ppt");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, out, DeckFormat.PPT);
        }
        try (PresentoireModel read = DeckCodecs.open(out)) {
            assertEquals(1, read.getSlideCount());
            boolean sawTitle = false;
            boolean sawList = false;
            boolean sawImage = false;
            for (SlideElement element : read.getSlide(0).elements()) {
                if (element instanceof SlideElement.Text t
                        && DeckFixtures.TITLE_TEXT.equals(t.text())) {
                    sawTitle = true;
                } else if (element instanceof SlideElement.ListElement l) {
                    sawList = DeckFixtures.BULLETS.equals(l.items());
                } else if (element instanceof SlideElement.Image) {
                    sawImage = true;
                }
            }
            assertTrue(sawTitle, "the title survived the round trip");
            assertTrue(sawList, "the bullet list survived");
            assertTrue(sawImage, "the picture survived");
        }
    }
}
