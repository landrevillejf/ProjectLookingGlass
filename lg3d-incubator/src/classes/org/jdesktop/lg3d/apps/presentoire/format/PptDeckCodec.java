/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.presentoire.format;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.sl.usermodel.PictureData;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;

/**
 * Reads and writes the legacy binary Microsoft PowerPoint {@code .ppt}
 * (97-2003) format through Apache POI's HSLF API.
 *
 * <p>This is the {@code .pptx} codec's twin for the older container: the mapping
 * is identical - a text shape becomes a {@link SlideElement.Text} or, when any
 * paragraph is bulleted, a {@link SlideElement.ListElement}; a picture shape
 * becomes a {@link SlideElement.Image} whose bytes ride in the model's in-memory
 * image map - only the POI classes differ. Export builds a fresh
 * {@link HSLFSlideShow} the same way, placing and colouring each shape with
 * {@link SlideGeometry} and {@link PptMapping}.</p>
 *
 * <p><strong>Fidelity.</strong> Exactly as for {@code .pptx}: only text, bullet
 * lists and pictures survive, themes / masters / fonts / animations / tables /
 * charts / grouped shapes are dropped, and export derives font size from element
 * height. The legacy format carries the same Presentoire vocabulary, no more.</p>
 */
final class PptDeckCodec {

    /** The bullet glyph a legacy list paragraph is marked with on export. */
    private static final char BULLET_CHAR = '\u2022';

    private PptDeckCodec() {
        // no instances
    }

    /**
     * Reads a legacy {@code .ppt} deck into the shared model.
     *
     * @param file the PowerPoint file
     * @return an in-memory model carrying the extracted pictures
     * @throws IOException if the file is not a readable {@code .ppt}
     */
    static PresentoireModel read(File file) throws IOException {
        try (FileInputStream fin = new FileInputStream(file);
                HSLFSlideShow ppt = new HSLFSlideShow(fin)) {
            Dimension page = ppt.getPageSize();
            SlideGeometry geo = new SlideGeometry(page.getWidth(), page.getHeight());
            List<SlideModel> slides = new ArrayList<>();
            Map<String, byte[]> images = new LinkedHashMap<>();
            int index = 0;
            for (HSLFSlide slide : ppt.getSlides()) {
                index++;
                List<SlideElement> elements = new ArrayList<>();
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        addIfPresent(elements, readText(geo, textShape));
                    } else if (shape instanceof HSLFPictureShape pictureShape) {
                        addIfPresent(elements, readPicture(geo, pictureShape, images));
                    }
                }
                slides.add(new SlideModel("slide-" + index, "", elements));
            }
            return PresentoireModel.of(slides, null, images);
        }
    }

    /**
     * Writes a model to a legacy {@code .ppt} deck on the default 10in x 7.5in
     * page.
     *
     * @param model  the deck to write; not closed by this call
     * @param target the file to create or overwrite
     * @throws IOException if the file cannot be written
     */
    static void write(PresentoireModel model, File target) throws IOException {
        try (HSLFSlideShow ppt = new HSLFSlideShow()) {
            Dimension page = new Dimension(
                    (int) Math.round(SlideGeometry.PPT_PAGE_WIDTH_PT),
                    (int) Math.round(SlideGeometry.PPT_PAGE_HEIGHT_PT));
            ppt.setPageSize(page);
            SlideGeometry geo = new SlideGeometry(page.getWidth(), page.getHeight());
            for (SlideModel slideModel : model.getSlides()) {
                HSLFSlide slide = ppt.createSlide();
                for (SlideElement element : slideModel.elements()) {
                    if (element instanceof SlideElement.Text text) {
                        writeText(geo, slide, text);
                    } else if (element instanceof SlideElement.ListElement list) {
                        writeList(geo, slide, list);
                    } else if (element instanceof SlideElement.Image image) {
                        writePicture(ppt, geo, slide, model, image);
                    }
                }
            }
            try (OutputStream out = new FileOutputStream(target)) {
                ppt.write(out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    private static SlideElement readText(SlideGeometry geo, HSLFTextShape shape) {
        Rectangle2D anchor = shape.getAnchor();
        if (anchor == null) {
            return null;
        }
        List<String> paragraphs = new ArrayList<>();
        boolean bulleted = false;
        int argb = ModelColor.BLACK;
        boolean haveColor = false;
        for (HSLFTextParagraph para : shape.getTextParagraphs()) {
            if (para.isBullet()) {
                bulleted = true;
            }
            String text = paragraphText(para);
            paragraphs.add((text == null) ? "" : text);
            if (!haveColor) {
                for (HSLFTextRun run : para.getTextRuns()) {
                    argb = PptMapping.argb(run.getFontColor());
                    haveColor = true;
                    break;
                }
            }
        }
        trimTrailingBlanks(paragraphs);
        if (paragraphs.isEmpty()) {
            return null;
        }
        SlideGeometry.Box box = geo.importTextBox(
                anchor.getX(), anchor.getY(), anchor.getWidth(), anchor.getHeight());
        if (bulleted) {
            List<String> items = new ArrayList<>();
            for (String paragraph : paragraphs) {
                String trimmed = paragraph.trim();
                if (!trimmed.isEmpty()) {
                    items.add(trimmed);
                }
            }
            if (items.isEmpty()) {
                return null;
            }
            return new SlideElement.ListElement(box.x(), box.y(), SlideElement.DEFAULT_Z,
                    box.width(), box.height(), argb, items);
        }
        String joined = String.join("\n", paragraphs).trim();
        if (joined.isEmpty()) {
            return null;
        }
        return new SlideElement.Text(box.x(), box.y(), SlideElement.DEFAULT_Z,
                box.width(), box.height(), argb, joined);
    }

    private static SlideElement readPicture(SlideGeometry geo, HSLFPictureShape shape,
            Map<String, byte[]> images) {
        HSLFPictureData data = shape.getPictureData();
        if (data == null) {
            return null;
        }
        byte[] bytes = data.getData();
        Rectangle2D anchor = shape.getAnchor();
        if (bytes == null || bytes.length == 0 || anchor == null) {
            return null;
        }
        String ext = PptMapping.extensionFor(data.getType());
        String src = "img/" + (images.size() + 1) + "." + ext;
        images.put(src, bytes);
        SlideGeometry.Box box = geo.importImageBox(
                anchor.getX(), anchor.getY(), anchor.getWidth(), anchor.getHeight());
        return new SlideElement.Image(box.x(), box.y(), SlideElement.DEFAULT_Z,
                box.width(), box.height(), src);
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private static void writeText(SlideGeometry geo, HSLFSlide slide, SlideElement.Text text) {
        if (text.text().trim().isEmpty()) {
            return;
        }
        String[] lines = text.lines();
        HSLFTextBox box = newTextBox(geo, slide, text);
        box.setText(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            box.appendText(lines[i], true);
        }
        double fontSize = PptMapping.fontSize(box.getAnchor(), lines.length);
        Color color = new Color(text.argb(), true);
        styleParagraphs(box, color, fontSize, false);
    }

    private static void writeList(SlideGeometry geo, HSLFSlide slide, SlideElement.ListElement list) {
        List<String> items = list.items();
        if (items.isEmpty()) {
            return;
        }
        HSLFTextBox box = newTextBox(geo, slide, list);
        box.setText(items.get(0));
        for (int i = 1; i < items.size(); i++) {
            box.appendText(items.get(i), true);
        }
        double fontSize = PptMapping.fontSize(box.getAnchor(), items.size());
        Color color = new Color(list.argb(), true);
        styleParagraphs(box, color, fontSize, true);
    }

    /** Applies colour, size and (optionally) a bullet to every run of the box. */
    private static void styleParagraphs(HSLFTextBox box, Color color, double fontSize,
            boolean bullets) {
        for (HSLFTextParagraph para : box.getTextParagraphs()) {
            if (bullets) {
                para.setBullet(true);
                para.setIndentLevel(0);
                para.setBulletChar(BULLET_CHAR);
                para.setBulletColor(color);
            }
            for (HSLFTextRun run : para.getTextRuns()) {
                run.setFontColor(color);
                run.setFontSize(fontSize);
            }
        }
    }

    private static HSLFTextBox newTextBox(SlideGeometry geo, HSLFSlide slide, SlideElement element) {
        SlideGeometry.Box box = geo.exportTextBox(
                element.x(), element.y(), element.width(), element.height());
        HSLFTextBox textBox = slide.createTextBox();
        textBox.setAnchor(new Rectangle2D.Double(box.x(), box.y(), box.width(), box.height()));
        textBox.setWordWrap(true);
        return textBox;
    }

    private static void writePicture(HSLFSlideShow ppt, SlideGeometry geo, HSLFSlide slide,
            PresentoireModel model, SlideElement.Image image) throws IOException {
        byte[] bytes = DeckImages.bytes(model, image.src());
        if (bytes == null) {
            return;
        }
        PictureData.PictureType type = PptMapping.pictureType(image.src());
        HSLFPictureData data = ppt.addPicture(bytes, type);
        HSLFPictureShape picture = slide.createPicture(data);
        SlideGeometry.Box box = geo.exportImageBox(
                image.x(), image.y(), image.width(), image.height());
        picture.setAnchor(new Rectangle2D.Double(box.x(), box.y(), box.width(), box.height()));
    }

    private static void addIfPresent(List<SlideElement> elements, SlideElement element) {
        if (element != null) {
            elements.add(element);
        }
    }

    /** Concatenates a paragraph's runs; HSLF exposes no instance {@code getText()}. */
    private static String paragraphText(HSLFTextParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (HSLFTextRun run : para.getTextRuns()) {
            String text = run.getRawText();
            if (text != null) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static void trimTrailingBlanks(List<String> paragraphs) {
        while (!paragraphs.isEmpty()
                && paragraphs.get(paragraphs.size() - 1).trim().isEmpty()) {
            paragraphs.remove(paragraphs.size() - 1);
        }
    }
}
