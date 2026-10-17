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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads and writes the LibreOffice / OpenOffice Impress {@code .odp}
 * (OpenDocument Presentation) format in pure Java - no ODF library, just a ZIP
 * and a DOM, exactly like the native codec.
 *
 * <p>An {@code .odp} is a ZIP whose {@code content.xml} holds an
 * {@code office:presentation} of {@code draw:page}s; each page carries
 * {@code draw:frame}s that wrap either a {@code draw:text-box} (a paragraph run
 * or a {@code text:list}) or a {@code draw:image} pointing at a
 * {@code Pictures/...} entry. Import maps a text-box to a
 * {@link SlideElement.Text} or, when it holds a list, a
 * {@link SlideElement.ListElement}, and a {@code draw:image} to a
 * {@link SlideElement.Image} whose bytes ride in the model's in-memory map; page
 * and frame geometry are in centimetres and pass through {@link SlideGeometry}.
 * Export writes a minimal but valid package - {@code mimetype} first and
 * uncompressed, then {@code META-INF/manifest.xml}, {@code content.xml} and the
 * {@code Pictures/...} images.</p>
 *
 * <p><strong>Fidelity.</strong> As for the PowerPoint codecs, only text, bullet
 * lists and pictures survive; master pages, styles beyond a per-element colour
 * and font size, animations, tables, charts and grouped shapes are dropped. Text
 * colour and a height-derived font size are emitted so an export looks close in
 * Impress, and colour is read back, so a text/list/picture deck round-trips.</p>
 */
final class OdpDeckCodec {

    private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String STYLE_NS = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String DRAW_NS = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String FO_NS =
            "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    private static final String SVG_NS =
            "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";

    /** Points per centimetre, to turn a height-derived size into {@code fo:font-size}. */
    private static final double CM_TO_PT = 72.0d / 2.54d;

    /** The graphics style every exported frame wears: no border, no fill, top-aligned. */
    private static final String GRAPHIC_STYLE =
            "<style:style style:name=\"G1\" style:family=\"graphic\">"
            + "<style:graphic-properties draw:stroke=\"none\" draw:fill=\"none\" "
            + "draw:textarea-vertical-align=\"top\" fo:padding-top=\"0.1cm\" "
            + "fo:padding-bottom=\"0.1cm\" fo:padding-left=\"0.1cm\" "
            + "fo:padding-right=\"0.1cm\"/></style:style>";

    /** The bullet list style every exported list references. */
    private static final String LIST_STYLE =
            "<text:list-style style:name=\"L1\">"
            + "<text:list-level-style-bullet text:level=\"1\" text:bullet-char=\"&#8226;\">"
            + "<style:list-level-properties text:space-before=\"0.4cm\" "
            + "text:min-label-width=\"0.4cm\"/></text:list-level-style-bullet>"
            + "</text:list-style>";

    private OdpDeckCodec() {
        // no instances
    }

    /**
     * Reads an {@code .odp} deck into the shared model.
     *
     * @param file the OpenDocument presentation
     * @return an in-memory model carrying the extracted pictures
     * @throws IOException if the file is not a readable {@code .odp}
     */
    static PresentoireModel read(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry content = zip.getEntry(PresentoireModel.CONTENT_ENTRY);
            if (content == null) {
                throw new IOException(
                        "Not an OpenDocument presentation (no content.xml): " + file.getName());
            }
            Document doc;
            try (InputStream in = zip.getInputStream(content)) {
                doc = parse(in);
            }
            Element root = doc.getDocumentElement();
            if (root == null || !"document-content".equals(root.getLocalName())) {
                throw new IOException("Not an OpenDocument presentation: " + file.getName());
            }
            double[] pageSize = readPageSize(doc);
            SlideGeometry geo = new SlideGeometry(pageSize[0], pageSize[1]);
            Map<String, Integer> colors = readStyleColors(doc);
            List<SlideModel> slides = new ArrayList<>();
            Map<String, byte[]> images = new LinkedHashMap<>();
            NodeList pages = doc.getElementsByTagNameNS(DRAW_NS, "page");
            for (int p = 0; p < pages.getLength(); p++) {
                Element page = (Element) pages.item(p);
                slides.add(readPage(geo, colors, page, zip, images, slides.size() + 1));
            }
            return PresentoireModel.of(slides, null, images);
        }
    }

    /**
     * Writes a model to an {@code .odp} deck on the default 28.0cm x 21.0cm page.
     *
     * @param model  the deck to write; not closed by this call
     * @param target the file to create or overwrite
     * @throws IOException if the file cannot be written
     */
    static void write(PresentoireModel model, File target) throws IOException {
        SlideGeometry geo = new SlideGeometry(
                SlideGeometry.ODP_PAGE_WIDTH_CM, SlideGeometry.ODP_PAGE_HEIGHT_CM);
        StringBuilder body = new StringBuilder();
        StringBuilder styles = new StringBuilder();
        List<String[]> pictures = new ArrayList<>(); // {path, mime}
        Map<String, byte[]> pictureBytes = new LinkedHashMap<>();
        int styleIndex = 0;
        int slideIndex = 0;
        int imageIndex = 0;
        for (SlideModel slide : model.getSlides()) {
            slideIndex++;
            body.append("<draw:page draw:name=\"slide").append(slideIndex)
                    .append("\" draw:style-name=\"dp1\" draw:master-page-name=\"Default\">\n");
            for (SlideElement element : slide.elements()) {
                if (element instanceof SlideElement.Text text) {
                    styleIndex = writeText(body, styles, geo, text, styleIndex);
                } else if (element instanceof SlideElement.ListElement list) {
                    styleIndex = writeList(body, styles, geo, list, styleIndex);
                } else if (element instanceof SlideElement.Image image) {
                    imageIndex = writeImage(body, geo, model, image, pictures,
                            pictureBytes, imageIndex);
                }
            }
            body.append("</draw:page>\n");
        }
        String contentXml = assembleContent(styles, body);
        String manifest = assembleManifest(pictures);
        try (OutputStream fout = new FileOutputStream(target);
                ZipOutputStream zip = new ZipOutputStream(fout, StandardCharsets.UTF_8)) {
            writeStored(zip, "mimetype",
                    DeckCodecs.ODP_MIMETYPE.getBytes(StandardCharsets.UTF_8));
            writeDeflated(zip, "META-INF/manifest.xml",
                    manifest.getBytes(StandardCharsets.UTF_8));
            writeDeflated(zip, PresentoireModel.CONTENT_ENTRY,
                    contentXml.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> entry : pictureBytes.entrySet()) {
                writeDeflated(zip, entry.getKey(), entry.getValue());
            }
        }
    }

    // ------------------------------------------------------------------
    // Import helpers
    // ------------------------------------------------------------------

    private static SlideModel readPage(SlideGeometry geo, Map<String, Integer> colors,
            Element page, ZipFile zip, Map<String, byte[]> images, int index) {
        List<SlideElement> elements = new ArrayList<>();
        for (Element frame : childElements(page, DRAW_NS, "frame")) {
            double x = lengthCm(frame.getAttributeNS(SVG_NS, "x"), 0d);
            double y = lengthCm(frame.getAttributeNS(SVG_NS, "y"), 0d);
            double w = lengthCm(frame.getAttributeNS(SVG_NS, "width"), 0d);
            double h = lengthCm(frame.getAttributeNS(SVG_NS, "height"), 0d);
            Element image = firstChildElement(frame, DRAW_NS, "image");
            if (image != null) {
                SlideElement element = readImage(geo, image, zip, images, x, y, w, h);
                if (element != null) {
                    elements.add(element);
                }
                continue;
            }
            Element textBox = firstChildElement(frame, DRAW_NS, "text-box");
            if (textBox != null) {
                SlideElement element = readTextBox(geo, colors, textBox, x, y, w, h);
                if (element != null) {
                    elements.add(element);
                }
            }
        }
        String name = page.getAttributeNS(DRAW_NS, "name");
        if (name == null || name.isEmpty()) {
            name = "slide-" + index;
        }
        return new SlideModel(name, "", elements);
    }

    private static SlideElement readTextBox(SlideGeometry geo, Map<String, Integer> colors,
            Element textBox, double x, double y, double w, double h) {
        Element list = firstChildElement(textBox, TEXT_NS, "list");
        SlideGeometry.Box box = geo.importTextBox(x, y, w, h);
        if (list != null) {
            List<String> items = new ArrayList<>();
            int argb = ModelColor.BLACK;
            boolean haveColor = false;
            for (Element item : childElements(list, TEXT_NS, "list-item")) {
                StringBuilder text = new StringBuilder();
                for (Element para : paragraphChildren(item)) {
                    if (!haveColor) {
                        argb = resolveColor(para, colors);
                        haveColor = true;
                    }
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(para.getTextContent());
                }
                String value = text.toString();
                if (!value.trim().isEmpty()) {
                    items.add(value);
                }
            }
            if (items.isEmpty()) {
                return null;
            }
            return new SlideElement.ListElement(box.x(), box.y(), SlideElement.DEFAULT_Z,
                    box.width(), box.height(), argb, items);
        }
        List<String> lines = new ArrayList<>();
        int argb = ModelColor.BLACK;
        boolean haveColor = false;
        for (Element para : paragraphChildren(textBox)) {
            if (!haveColor) {
                argb = resolveColor(para, colors);
                haveColor = true;
            }
            lines.add(para.getTextContent());
        }
        String joined = String.join("\n", lines).trim();
        if (joined.isEmpty()) {
            return null;
        }
        return new SlideElement.Text(box.x(), box.y(), SlideElement.DEFAULT_Z,
                box.width(), box.height(), argb, joined);
    }

    private static SlideElement readImage(SlideGeometry geo, Element image, ZipFile zip,
            Map<String, byte[]> images, double x, double y, double w, double h) {
        String href = image.getAttributeNS(XLINK_NS, "href");
        if (href == null || href.isEmpty()) {
            return null;
        }
        if (href.startsWith("./")) {
            href = href.substring(2);
        }
        byte[] bytes = readEntry(zip, href);
        if (bytes == null) {
            return null;
        }
        String ext = DeckImages.extension(href);
        String src = "img/" + (images.size() + 1) + "." + ext;
        images.put(src, bytes);
        SlideGeometry.Box box = geo.importImageBox(x, y, w, h);
        return new SlideElement.Image(box.x(), box.y(), SlideElement.DEFAULT_Z,
                box.width(), box.height(), src);
    }

    /** Reads the first page-layout's size in cm, defaulting to the ODP page. */
    private static double[] readPageSize(Document doc) {
        double width = SlideGeometry.ODP_PAGE_WIDTH_CM;
        double height = SlideGeometry.ODP_PAGE_HEIGHT_CM;
        NodeList layouts = doc.getElementsByTagNameNS(STYLE_NS, "page-layout-properties");
        if (layouts.getLength() > 0) {
            Element props = (Element) layouts.item(0);
            width = lengthCm(props.getAttributeNS(FO_NS, "page-width"), width);
            height = lengthCm(props.getAttributeNS(FO_NS, "page-height"), height);
        }
        return new double[] {width, height};
    }

    /** Maps each automatic paragraph/text style name to its {@code fo:color}. */
    private static Map<String, Integer> readStyleColors(Document doc) {
        Map<String, Integer> colors = new LinkedHashMap<>();
        NodeList styles = doc.getElementsByTagNameNS(STYLE_NS, "style");
        for (int i = 0; i < styles.getLength(); i++) {
            Element style = (Element) styles.item(i);
            String family = style.getAttributeNS(STYLE_NS, "family");
            if (!"paragraph".equals(family) && !"text".equals(family)) {
                continue;
            }
            String name = style.getAttributeNS(STYLE_NS, "name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            Element textProps = firstChildElement(style, STYLE_NS, "text-properties");
            if (textProps == null) {
                continue;
            }
            String color = textProps.getAttributeNS(FO_NS, "color");
            if (color != null && !color.isEmpty()) {
                colors.put(name, ModelColor.parse(color));
            }
        }
        return colors;
    }

    private static int resolveColor(Element para, Map<String, Integer> colors) {
        String styleName = para.getAttributeNS(TEXT_NS, "style-name");
        Integer argb = (styleName == null) ? null : colors.get(styleName);
        return (argb == null) ? ModelColor.BLACK : argb;
    }

    // ------------------------------------------------------------------
    // Export helpers
    // ------------------------------------------------------------------

    private static int writeText(StringBuilder body, StringBuilder styles, SlideGeometry geo,
            SlideElement.Text text, int styleIndex) {
        if (text.text().trim().isEmpty()) {
            return styleIndex;
        }
        String[] lines = text.lines();
        int index = styleIndex + 1;
        SlideGeometry.Box box = geo.exportTextBox(
                text.x(), text.y(), text.width(), text.height());
        appendStyle(styles, index, text.argb(), box.height(), lines.length);
        appendFrameOpen(body, box);
        body.append("<draw:text-box>\n");
        for (String line : lines) {
            appendParagraph(body, index, line);
        }
        body.append("</draw:text-box>\n</draw:frame>\n");
        return index;
    }

    private static int writeList(StringBuilder body, StringBuilder styles, SlideGeometry geo,
            SlideElement.ListElement list, int styleIndex) {
        List<String> items = list.items();
        if (items.isEmpty()) {
            return styleIndex;
        }
        int index = styleIndex + 1;
        SlideGeometry.Box box = geo.exportTextBox(
                list.x(), list.y(), list.width(), list.height());
        appendStyle(styles, index, list.argb(), box.height(), items.size());
        appendFrameOpen(body, box);
        body.append("<draw:text-box>\n<text:list text:style-name=\"L1\">\n");
        for (String item : items) {
            body.append("<text:list-item>");
            body.append("<text:p text:style-name=\"P").append(index).append("\">")
                    .append(Xml.escape(item)).append("</text:p>");
            body.append("</text:list-item>\n");
        }
        body.append("</text:list>\n</draw:text-box>\n</draw:frame>\n");
        return index;
    }

    private static int writeImage(StringBuilder body, SlideGeometry geo, PresentoireModel model,
            SlideElement.Image image, List<String[]> pictures,
            Map<String, byte[]> pictureBytes, int imageIndex) {
        byte[] bytes = DeckImages.bytes(model, image.src());
        if (bytes == null) {
            return imageIndex;
        }
        int index = imageIndex + 1;
        String ext = DeckImages.extension(image.src());
        String path = "Pictures/" + index + "." + ext;
        String mime = mimeType(ext);
        pictures.add(new String[] {path, mime});
        pictureBytes.put(path, bytes);
        SlideGeometry.Box box = geo.exportImageBox(
                image.x(), image.y(), image.width(), image.height());
        appendFrameOpen(body, box);
        body.append("<draw:image xlink:href=\"").append(Xml.escape(path))
                .append("\" xlink:type=\"simple\" xlink:show=\"embed\" ")
                .append("xlink:actuate=\"onLoad\" draw:mime-type=\"").append(mime)
                .append("\"/>\n</draw:frame>\n");
        return index;
    }

    private static void appendFrameOpen(StringBuilder body, SlideGeometry.Box box) {
        body.append("<draw:frame draw:style-name=\"G1\" draw:layer=\"layout\" svg:x=\"")
                .append(cm(box.x())).append("\" svg:y=\"").append(cm(box.y()))
                .append("\" svg:width=\"").append(cm(box.width()))
                .append("\" svg:height=\"").append(cm(box.height())).append("\">\n");
    }

    private static void appendParagraph(StringBuilder body, int styleIndex, String text) {
        body.append("<text:p text:style-name=\"P").append(styleIndex).append("\">")
                .append(Xml.escape(text)).append("</text:p>\n");
    }

    private static void appendStyle(StringBuilder styles, int index, int argb,
            double heightCm, int lineCount) {
        double perLineCm = heightCm / Math.max(1, lineCount);
        double fontPt = Math.max(1d, perLineCm * 0.72d * CM_TO_PT);
        String color = String.format(Locale.ROOT, "#%02X%02X%02X",
                ModelColor.red(argb), ModelColor.green(argb), ModelColor.blue(argb));
        styles.append("<style:style style:name=\"P").append(index)
                .append("\" style:family=\"paragraph\"><style:text-properties fo:color=\"")
                .append(color).append("\" fo:font-size=\"")
                .append(String.format(Locale.ROOT, "%.2f", fontPt)).append("pt\"/>")
                .append("</style:style>\n");
    }

    private static String assembleContent(StringBuilder styles, StringBuilder body) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<office:document-content ")
                .append("xmlns:office=\"").append(OFFICE_NS).append("\" ")
                .append("xmlns:style=\"").append(STYLE_NS).append("\" ")
                .append("xmlns:text=\"").append(TEXT_NS).append("\" ")
                .append("xmlns:draw=\"").append(DRAW_NS).append("\" ")
                .append("xmlns:fo=\"").append(FO_NS).append("\" ")
                .append("xmlns:svg=\"").append(SVG_NS).append("\" ")
                .append("xmlns:xlink=\"").append(XLINK_NS).append("\" ")
                .append("xmlns:presentation=\"urn:oasis:names:tc:opendocument:")
                .append("xmlns:presentation:1.0\" office:version=\"1.2\">\n");
        xml.append("<office:automatic-styles>\n");
        xml.append("<style:page-layout style:name=\"PM0\"><style:page-layout-properties ")
                .append("fo:page-width=\"").append(cm(SlideGeometry.ODP_PAGE_WIDTH_CM))
                .append("\" fo:page-height=\"").append(cm(SlideGeometry.ODP_PAGE_HEIGHT_CM))
                .append("\" fo:margin-top=\"0cm\" fo:margin-bottom=\"0cm\" ")
                .append("fo:margin-left=\"0cm\" fo:margin-right=\"0cm\" ")
                .append("fo:background-color=\"#FFFFFF\"/></style:page-layout>\n");
        xml.append("<style:style style:name=\"dp1\" style:family=\"drawing-page\">")
                .append("<style:drawing-page-properties draw:background-size=\"border\"/>")
                .append("</style:style>\n");
        xml.append("<style:master-page style:name=\"Default\" style:page-layout-name=\"PM0\"/>\n");
        xml.append(GRAPHIC_STYLE).append('\n');
        xml.append(LIST_STYLE).append('\n');
        xml.append(styles);
        xml.append("</office:automatic-styles>\n");
        xml.append("<office:body>\n<office:presentation>\n");
        xml.append(body);
        xml.append("</office:presentation>\n</office:body>\n");
        xml.append("</office:document-content>\n");
        return xml.toString();
    }

    private static String assembleManifest(List<String[]> pictures) {
        StringBuilder man = new StringBuilder();
        man.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        man.append("<manifest:manifest ")
                .append("xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" ")
                .append("manifest:version=\"1.2\">\n");
        man.append("<manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.2\" ")
                .append("manifest:media-type=\"").append(DeckCodecs.ODP_MIMETYPE).append("\"/>\n");
        man.append("<manifest:file-entry manifest:full-path=\"")
                .append(PresentoireModel.CONTENT_ENTRY)
                .append("\" manifest:media-type=\"text/xml\"/>\n");
        for (String[] picture : pictures) {
            man.append("<manifest:file-entry manifest:full-path=\"")
                    .append(Xml.escape(picture[0])).append("\" manifest:media-type=\"")
                    .append(picture[1]).append("\"/>\n");
        }
        man.append("</manifest:manifest>\n");
        return man.toString();
    }

    private static String mimeType(String ext) {
        switch (ext) {
            case "jpg":
            case "jpeg":
            case "jpe":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "tif":
            case "tiff":
                return "image/tiff";
            case "svg":
                return "image/svg+xml";
            default:
                return "image/png";
        }
    }

    // ------------------------------------------------------------------
    // ZIP + DOM plumbing
    // ------------------------------------------------------------------

    /** Writes the {@code mimetype} entry first, STORED, as the ODF spec requires. */
    private static void writeStored(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void writeDeflated(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] readEntry(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Document parse(InputStream in) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            trySet(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySet(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySet(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            trySet(factory, "http://xml.org/sax/features/load-external-dtd", false);
            return factory.newDocumentBuilder().parse(in);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Not a valid OpenDocument content.xml: " + e.getMessage(), e);
        }
    }

    private static void trySet(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            // the parser does not support this hardening flag; carry on
        }
    }

    private static List<Element> childElements(Element parent, String ns, String local) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && matches((Element) n, ns, local)) {
                found.add((Element) n);
            }
        }
        return found;
    }

    private static Element firstChildElement(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && matches((Element) n, ns, local)) {
                return (Element) n;
            }
        }
        return null;
    }

    /** The {@code text:p} and {@code text:h} children of a box or list item. */
    private static List<Element> paragraphChildren(Element parent) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            if (matches(e, TEXT_NS, "p") || matches(e, TEXT_NS, "h")) {
                found.add(e);
            }
        }
        return found;
    }

    private static boolean matches(Element element, String ns, String local) {
        return local.equals(element.getLocalName()) && ns.equals(element.getNamespaceURI());
    }

    /** Parses an ODF length (cm, mm, in, pt, pc, px) into centimetres. */
    private static double lengthCm(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return fallback;
        }
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+') {
                i++;
            } else {
                break;
            }
        }
        double n;
        try {
            n = Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
        String unit = s.substring(i).trim().toLowerCase(Locale.ROOT);
        switch (unit) {
            case "mm":
                return n / 10d;
            case "in":
                return n * 2.54d;
            case "pt":
                return n * 2.54d / 72d;
            case "pc":
                return n * 2.54d / 6d;
            case "px":
                return n * 2.54d / 96d;
            default:
                return n; // "cm", blank or an unknown unit: treat as centimetres
        }
    }

    /** Formats a centimetre length with four decimals. */
    private static String cm(double value) {
        return String.format(Locale.ROOT, "%.4f", value) + "cm";
    }
}
