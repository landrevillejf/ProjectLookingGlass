package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the {@code .pptx} (Office Open XML) codec through {@link DeckCodecs},
 * against genuine Apache POI files. Import maps a text box to a
 * {@link SlideElement.Text}, a bulleted body to a {@link SlideElement.ListElement}
 * and a picture to a {@link SlideElement.Image} whose bytes ride in the model;
 * export writes a fresh {@code .pptx} POI can re-open with the same text, bullets
 * and picture. The fit-inside coordinate math keeps every box on the
 * {@code -0.1 .. 0.1} slide. Headless: POI extracts text and picture bytes
 * without rendering.
 */
class PptxDeckCodecTest {

    @Test
    @DisplayName("importing a POI-built .pptx maps its title, bullets and picture")
    void importsAForeignDeck(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF0000FF, 4, 4);
        File pptx = new File(dir, "foreign.pptx");
        DeckFixtures.buildPptx(pptx, png);

        try (PresentoireModel model = DeckCodecs.open(pptx)) {
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
            assertEquals(DeckFixtures.TITLE_ARGB, text.argb(), "the explicit colour survived");

            assertNotNull(list, "the bulleted body imported as a list");
            assertEquals(DeckFixtures.BULLETS, list.items());

            assertNotNull(image, "the picture imported");
            BufferedImage loaded = model.loadImage(image.src());
            assertNotNull(loaded, "and its bytes ride in the in-memory model");
            assertEquals(0xFF0000FF, loaded.getRGB(0, 0));

            // The fit-inside mapping keeps every box on the square slide.
            DeckFixtures.assertTextWithinSlide(text);
            DeckFixtures.assertTextWithinSlide(list);
            DeckFixtures.assertImageWithinSlide(image);
        }
    }

    @Test
    @DisplayName("exporting writes a .pptx POI re-opens with the same text, bullets and picture")
    void exportsAReopenableDeck(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFFFF0000, 4, 4);
        File out = new File(dir, "export.pptx");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, out, DeckFormat.PPTX);
        }
        assertTrue(out.length() > 0, "the export produced a file");

        try (FileInputStream fin = new FileInputStream(out);
                XMLSlideShow ppt = new XMLSlideShow(fin)) {
            assertEquals(1, ppt.getSlides().size());
            XSLFSlide slide = ppt.getSlides().get(0);

            StringBuilder text = new StringBuilder();
            boolean bulleted = false;
            boolean picture = false;
            for (XSLFShape shape : slide.getShapes()) {
                if (shape instanceof XSLFTextShape textShape) {
                    for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                        if (para.isBullet()) {
                            bulleted = true;
                        }
                        text.append(para.getText()).append('\n');
                    }
                } else if (shape instanceof XSLFPictureShape) {
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
        File out = new File(dir, "round.pptx");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, out, DeckFormat.PPTX);
        }
        try (PresentoireModel read = DeckCodecs.open(out)) {
            assertEquals(1, read.getSlideCount());
            SlideModel slide = read.getSlide(0);
            boolean sawTitle = false;
            boolean sawList = false;
            boolean sawImage = false;
            for (SlideElement element : slide.elements()) {
                if (element instanceof SlideElement.Text t
                        && DeckFixtures.TITLE_TEXT.equals(t.text())) {
                    sawTitle = true;
                    assertEquals(DeckFixtures.TITLE_ARGB, t.argb());
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
