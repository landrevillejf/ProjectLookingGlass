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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;

/**
 * Reads and writes Presentoire's own {@code .zip} deck format.
 *
 * <p>Reading is exactly {@link PresentoireModel#open(File)} - the parser both
 * desktops already share. Writing is new: it serialises the model back to a
 * {@code content.xml} ({@code <document>} / {@code <slide>} / {@code <text>},
 * {@code <img>}, {@code <ul>}) with the same attribute vocabulary and
 * {@link ModelColor#toHex(int)} colours the parser reads, and copies every
 * picture the slides reference into the archive under its {@code src} path. A
 * native deck therefore round-trips: write then read returns the same slides,
 * elements, colours and image pixels.</p>
 *
 * <p>Because it writes the same vocabulary it reads, this codec also gives the
 * other codecs a common target: an imported PowerPoint or LibreOffice deck is
 * re-expressed in Presentoire's own text / list / image terms, and saving it
 * natively captures exactly what the renderers can show. Anything outside that
 * vocabulary was already dropped at import, so nothing further is lost here.</p>
 */
final class NativeDeckCodec {

    private NativeDeckCodec() {
        // no instances
    }

    /**
     * Reads a native {@code .zip} deck.
     *
     * @param file the deck archive, which must contain a {@code content.xml}
     * @return the parsed model, holding the ZIP open for image loading
     * @throws IOException if the file is not a readable, parseable deck
     */
    static PresentoireModel read(File file) throws IOException {
        return PresentoireModel.open(file);
    }

    /**
     * Writes a model to a native {@code .zip} deck.
     *
     * @param model  the deck to serialise; not closed by this call
     * @param target the archive to create or overwrite
     * @throws IOException if the archive cannot be written
     */
    static void write(PresentoireModel model, File target) throws IOException {
        String contentXml = toContentXml(model);
        try (ZipOutputStream zip = new ZipOutputStream(
                new FileOutputStream(target), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(PresentoireModel.CONTENT_ENTRY));
            zip.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            writeImages(model, zip);
        }
    }

    /** Collects every referenced image's bytes into the archive, once per src. */
    private static void writeImages(PresentoireModel model, ZipOutputStream zip)
            throws IOException {
        Set<String> written = new HashSet<>();
        for (SlideModel slide : model.getSlides()) {
            for (SlideElement element : slide.elements()) {
                if (!(element instanceof SlideElement.Image image)) {
                    continue;
                }
                String src = image.src();
                if (src.isEmpty() || !written.add(src)) {
                    continue;
                }
                byte[] bytes = DeckImages.bytes(model, src);
                if (bytes == null) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(src));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    /** Serialises the whole deck to its {@code content.xml} text. */
    private static String toContentXml(PresentoireModel model) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<document>\n");
        for (SlideModel slide : model.getSlides()) {
            appendSlide(xml, slide);
        }
        xml.append("</document>\n");
        return xml.toString();
    }

    private static void appendSlide(StringBuilder xml, SlideModel slide) {
        xml.append("  <slide");
        appendAttr(xml, "id", slide.id());
        if (slide.hasTransition()) {
            appendAttr(xml, "transition", slide.transition());
        }
        List<SlideElement> elements = slide.elements();
        if (elements.isEmpty()) {
            xml.append("/>\n");
            return;
        }
        xml.append(">\n");
        for (SlideElement element : elements) {
            appendElement(xml, element);
        }
        xml.append("  </slide>\n");
    }

    private static void appendElement(StringBuilder xml, SlideElement element) {
        if (element instanceof SlideElement.Text text) {
            xml.append("    <text");
            appendGeometry(xml, element);
            appendAttr(xml, "color", ModelColor.toHex(text.argb()));
            xml.append('>').append(Xml.escape(text.text())).append("</text>\n");
        } else if (element instanceof SlideElement.ListElement list) {
            xml.append("    <ul");
            appendGeometry(xml, element);
            appendAttr(xml, "color", ModelColor.toHex(list.argb()));
            xml.append(">\n");
            for (String item : list.items()) {
                xml.append("      <li>").append(Xml.escape(item)).append("</li>\n");
            }
            xml.append("    </ul>\n");
        } else if (element instanceof SlideElement.Image image) {
            xml.append("    <img");
            appendGeometry(xml, element);
            appendAttr(xml, "src", image.src());
            xml.append("/>\n");
        }
    }

    /** Emits {@code x y [z] width height}, with z only when it is non-default. */
    private static void appendGeometry(StringBuilder xml, SlideElement element) {
        appendNumAttr(xml, "x", element.x());
        appendNumAttr(xml, "y", element.y());
        if (element.z() != SlideElement.DEFAULT_Z) {
            appendNumAttr(xml, "z", element.z());
        }
        appendNumAttr(xml, "width", element.width());
        appendNumAttr(xml, "height", element.height());
    }

    private static void appendAttr(StringBuilder xml, String name, String value) {
        xml.append(' ').append(name).append("=\"").append(Xml.escape(value)).append('"');
    }

    private static void appendNumAttr(StringBuilder xml, String name, float value) {
        xml.append(' ').append(name).append("=\"").append(num(value)).append('"');
    }

    /** Formats a slide-unit float to at most 6 decimals, with no trailing zeros. */
    private static String num(float value) {
        String s = String.format(Locale.ROOT, "%.6f", value);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return ("-0".equals(s)) ? "0" : s;
    }
}
