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
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;

/**
 * Reads and writes the modern Microsoft PowerPoint {@code .pptx} (Office Open
 * XML) format through Apache POI's XSLF API.
 *
 * <p>Import walks each slide's shapes and keeps only what Presentoire can show:
 * an {@link XSLFTextShape} becomes a {@link SlideElement.Text}, or a
 * {@link SlideElement.ListElement} when any of its paragraphs is bulleted, and an
 * {@link XSLFPictureShape} becomes a {@link SlideElement.Image} whose bytes are
 * carried in the model's in-memory image map. Export reverses that into a fresh
 * {@link XMLSlideShow}: one text box per text block, one bulleted text box per
 * list, one picture per image, each placed by {@link SlideGeometry} and coloured
 * from the element's ARGB.</p>
 *
 * <p><strong>Fidelity.</strong> Only text, bullet lists and pictures survive -
 * the Presentoire vocabulary. Themes, masters, custom fonts, animations,
 * transitions, tables, charts, grouped / rotated / auto-fit shapes, speaker notes
 * and non-solid fills are intentionally dropped on import and never emitted on
 * export; font size on export is derived from the element's height (see
 * {@link PptMapping#fontSize}). A deck that uses only these three element kinds
 * round-trips; a richly-styled PowerPoint opens as a faithful text-and-picture
 * outline, not a pixel copy.</p>
 */
final class PptxDeckCodec {

    private PptxDeckCodec() {
        // no instances
    }

    /**
     * Reads a {@code .pptx} deck into the shared model.
     *
     * @param file the PowerPoint file
     * @return an in-memory model carrying the extracted pictures
     * @throws IOException if the file is not a readable {@code .pptx}
     */
    static PresentoireModel read(File file) throws IOException {
        try (FileInputStream fin = new FileInputStream(file);
                XMLSlideShow ppt = new XMLSlideShow(fin)) {
            Dimension page = ppt.getPageSize();
            SlideGeometry geo = new SlideGeometry(page.getWidth(), page.getHeight());
            List<SlideModel> slides = new ArrayList<>();
            Map<String, byte[]> images = new LinkedHashMap<>();
            int index = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                index++;
                List<SlideElement> elements = new ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        addIfPresent(elements, readText(geo, textShape));
                    } else if (shape instanceof XSLFPictureShape pictureShape) {
                        addIfPresent(elements, readPicture(geo, pictureShape, images));
                    }
                }
                String id = slide.getSlideName();
                if (id == null || id.isEmpty()) {
                    id = "slide-" + index;
                }
                slides.add(new SlideModel(id, "", elements));
            }
            return PresentoireModel.of(slides, null, images);
        }
    }

    /**
     * Writes a model to a {@code .pptx} deck on POI's default 10in x 7.5in page.
     *
     * @param model  the deck to write; not closed by this call
     * @param target the file to create or overwrite
     * @throws IOException if the file cannot be written
     */
    static void write(PresentoireModel model, File target) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            Dimension page = new Dimension(
                    (int) Math.round(SlideGeometry.PPT_PAGE_WIDTH_PT),
                    (int) Math.round(SlideGeometry.PPT_PAGE_HEIGHT_PT));
            ppt.setPageSize(page);
            SlideGeometry geo = new SlideGeometry(page.getWidth(), page.getHeight());
            for (SlideModel slideModel : model.getSlides()) {
                XSLFSlide slide = ppt.createSlide();
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

    private static SlideElement readText(SlideGeometry geo, XSLFTextShape shape) {
        Rectangle2D anchor = shape.getAnchor();
        if (anchor == null) {
            return null;
        }
        List<String> paragraphs = new ArrayList<>();
        boolean bulleted = false;
        int argb = ModelColor.BLACK;
        boolean haveColor = false;
        for (XSLFTextParagraph para : shape.getTextParagraphs()) {
            if (para.isBullet()) {
                bulleted = true;
            }
            String text = para.getText();
            paragraphs.add((text == null) ? "" : text);
            if (!haveColor) {
                for (XSLFTextRun run : para.getTextRuns()) {
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

    private static SlideElement readPicture(SlideGeometry geo, XSLFPictureShape shape,
            Map<String, byte[]> images) {
        XSLFPictureData data = shape.getPictureData();
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

    private static void writeText(SlideGeometry geo, XSLFSlide slide, SlideElement.Text text) {
        if (text.text().trim().isEmpty()) {
            return;
        }
        String[] lines = text.lines();
        XSLFTextBox box = newTextBox(geo, slide, text);
        double fontSize = PptMapping.fontSize(box.getAnchor(), lines.length);
        Color color = new Color(text.argb(), true);
        box.clearText();
        for (String line : lines) {
            XSLFTextRun run = box.addNewTextParagraph().addNewTextRun();
            run.setText(line);
            run.setFontColor(color);
            run.setFontSize(fontSize);
        }
    }

    private static void writeList(SlideGeometry geo, XSLFSlide slide, SlideElement.ListElement list) {
        List<String> items = list.items();
        if (items.isEmpty()) {
            return;
        }
        XSLFTextBox box = newTextBox(geo, slide, list);
        double fontSize = PptMapping.fontSize(box.getAnchor(), items.size());
        Color color = new Color(list.argb(), true);
        box.clearText();
        for (String item : items) {
            XSLFTextParagraph para = box.addNewTextParagraph();
            para.setBullet(true);
            para.setIndentLevel(0);
            para.setBulletFontColor(color);
            XSLFTextRun run = para.addNewTextRun();
            run.setText(item);
            run.setFontColor(color);
            run.setFontSize(fontSize);
        }
    }

    private static XSLFTextBox newTextBox(SlideGeometry geo, XSLFSlide slide, SlideElement element) {
        SlideGeometry.Box box = geo.exportTextBox(
                element.x(), element.y(), element.width(), element.height());
        XSLFTextBox textBox = slide.createTextBox();
        textBox.setAnchor(new Rectangle2D.Double(box.x(), box.y(), box.width(), box.height()));
        textBox.setWordWrap(true);
        return textBox;
    }

    private static void writePicture(XMLSlideShow ppt, SlideGeometry geo, XSLFSlide slide,
            PresentoireModel model, SlideElement.Image image) {
        byte[] bytes = DeckImages.bytes(model, image.src());
        if (bytes == null) {
            return;
        }
        PictureData.PictureType type = PptMapping.pictureType(image.src());
        XSLFPictureData data = ppt.addPicture(bytes, type);
        XSLFPictureShape picture = slide.createPicture(data);
        SlideGeometry.Box box = geo.exportImageBox(
                image.x(), image.y(), image.width(), image.height());
        picture.setAnchor(new Rectangle2D.Double(box.x(), box.y(), box.width(), box.height()));
    }

    private static void addIfPresent(List<SlideElement> elements, SlideElement element) {
        if (element != null) {
            elements.add(element);
        }
    }

    private static void trimTrailingBlanks(List<String> paragraphs) {
        while (!paragraphs.isEmpty()
                && paragraphs.get(paragraphs.size() - 1).trim().isEmpty()) {
            paragraphs.remove(paragraphs.size() - 1);
        }
    }
}
