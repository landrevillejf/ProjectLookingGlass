package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;

/**
 * Shared, headless builders and assertions for the Presentoire format codec
 * tests. Everything here needs no display: POI's text and picture handling is
 * pure Java, and {@link BufferedImage}/{@link ImageIO} rasterise off-screen.
 *
 * <p>The "foreign" {@code .pptx}/{@code .ppt} builders produce genuine Apache
 * POI files - the same shape a real PowerPoint deck has - so the import tests
 * exercise the codecs against files Presentoire did not write itself, while the
 * round-trip tests export through {@link DeckCodecs} and read the result back.</p>
 */
final class DeckFixtures {

    /** The title text the foreign PowerPoint builders plant on slide one. */
    static final String TITLE_TEXT = "Hello Presentoire";

    /** The explicit title colour the foreign PowerPoint builders set (#CC0000). */
    static final int TITLE_ARGB = 0xFFCC0000;

    /** The three bullet items the foreign PowerPoint builders plant. */
    static final List<String> BULLETS = List.of("alpha", "beta", "gamma");

    private DeckFixtures() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    /** Encodes a solid-colour PNG and returns its bytes. */
    static byte[] solidPng(int argb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // In-memory model (what the exporters consume)
    // ------------------------------------------------------------------

    /**
     * A one-slide in-memory model carrying the whole Presentoire vocabulary: a
     * coloured title text block, a three-item bullet list, and a red picture held
     * in memory. Every box lies inside the {@code -0.1 .. 0.1} slide.
     */
    static PresentoireModel sampleModel(byte[] png) {
        SlideElement.Text title = new SlideElement.Text(
                -0.08f, 0.03f, SlideElement.DEFAULT_Z, 0.16f, 0.03f,
                ModelColor.parse("#CC0000"), TITLE_TEXT);
        SlideElement.ListElement list = new SlideElement.ListElement(
                -0.08f, -0.02f, SlideElement.DEFAULT_Z, 0.16f, 0.05f,
                ModelColor.BLACK, BULLETS);
        SlideElement.Image image = new SlideElement.Image(
                0f, -0.06f, SlideElement.DEFAULT_Z, 0.05f, 0.04f, "img/red.png");
        SlideModel slide = new SlideModel("s1", "", List.of(title, list, image));
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("img/red.png", png);
        return PresentoireModel.of(List.of(slide), null, images);
    }

    // ------------------------------------------------------------------
    // Foreign PowerPoint decks (built with POI, as a real office would)
    // ------------------------------------------------------------------

    /** Writes a genuine {@code .pptx} (title + bulleted body + picture) via XSLF. */
    static void buildPptx(File file, byte[] png) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension(720, 540));
            XSLFSlide slide = ppt.createSlide();

            XSLFTextBox title = slide.createTextBox();
            title.setAnchor(new Rectangle2D.Double(72, 54, 576, 80));
            title.clearText();
            XSLFTextRun run = title.addNewTextParagraph().addNewTextRun();
            run.setText(TITLE_TEXT);
            run.setFontColor(new Color(0xCC, 0x00, 0x00));

            XSLFTextBox body = slide.createTextBox();
            body.setAnchor(new Rectangle2D.Double(72, 180, 576, 200));
            body.clearText();
            for (String item : BULLETS) {
                XSLFTextParagraph para = body.addNewTextParagraph();
                para.setBullet(true);
                para.addNewTextRun().setText(item);
            }

            XSLFPictureData data = ppt.addPicture(png, PictureData.PictureType.PNG);
            XSLFPictureShape picture = slide.createPicture(data);
            picture.setAnchor(new Rectangle2D.Double(100, 400, 120, 90));

            try (OutputStream out = new FileOutputStream(file)) {
                ppt.write(out);
            }
        }
    }

    /** Writes a genuine legacy {@code .ppt} (title + bulleted body + picture) via HSLF. */
    static void buildPpt(File file, byte[] png) throws IOException {
        try (HSLFSlideShow ppt = new HSLFSlideShow()) {
            ppt.setPageSize(new Dimension(720, 540));
            HSLFSlide slide = ppt.createSlide();

            HSLFTextBox title = slide.createTextBox();
            title.setAnchor(new Rectangle2D.Double(72, 54, 576, 80));
            title.setText(TITLE_TEXT);
            for (HSLFTextParagraph para : title.getTextParagraphs()) {
                for (HSLFTextRun run : para.getTextRuns()) {
                    run.setFontColor(new Color(0xCC, 0x00, 0x00));
                }
            }

            HSLFTextBox body = slide.createTextBox();
            body.setAnchor(new Rectangle2D.Double(72, 180, 576, 200));
            body.setText(BULLETS.get(0));
            for (int i = 1; i < BULLETS.size(); i++) {
                body.appendText(BULLETS.get(i), true);
            }
            for (HSLFTextParagraph para : body.getTextParagraphs()) {
                para.setBullet(true);
                para.setBulletChar('\u2022');
            }

            HSLFPictureData data = ppt.addPicture(png, PictureData.PictureType.PNG);
            HSLFPictureShape picture = slide.createPicture(data);
            picture.setAnchor(new Rectangle2D.Double(100, 400, 120, 90));

            try (OutputStream out = new FileOutputStream(file)) {
                ppt.write(out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Geometry assertions
    // ------------------------------------------------------------------

    /** Asserts a bottom-left-anchored text/list box lies within the slide. */
    static void assertTextWithinSlide(SlideElement e) {
        assertBoxWithinSlide(e.x(), e.y(), e.x() + e.width(), e.y() + e.height());
    }

    /** Asserts a centre-anchored image box lies within the slide. */
    static void assertImageWithinSlide(SlideElement.Image e) {
        assertBoxWithinSlide(e.x() - e.width() / 2f, e.y() - e.height() / 2f,
                e.x() + e.width() / 2f, e.y() + e.height() / 2f);
    }

    private static void assertBoxWithinSlide(float left, float bottom, float right, float top) {
        float eps = 2e-3f;
        assertTrue(left >= -0.1f - eps, "left edge " + left + " within the slide");
        assertTrue(bottom >= -0.1f - eps, "bottom edge " + bottom + " within the slide");
        assertTrue(right <= 0.1f + eps, "right edge " + right + " within the slide");
        assertTrue(top <= 0.1f + eps, "top edge " + top + " within the slide");
    }
}
