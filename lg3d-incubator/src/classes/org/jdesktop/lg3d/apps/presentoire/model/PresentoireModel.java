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
package org.jdesktop.lg3d.apps.presentoire.model;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * The AWT-free, Java 3D-free parser of a Presentoire slide deck.
 *
 * <p>A Presentoire document is a ZIP archive whose {@code content.xml} describes
 * an ordered list of {@code <slide>} elements (plus optional document-level
 * {@code <transition>} script definitions); each slide holds {@code <text>},
 * {@code <img>} and {@code <ul>} children, and an {@code <img>} names a picture
 * stored elsewhere in the same ZIP. This class turns that archive into an
 * immutable {@link SlideModel} tree and resolves slide images on demand, and it
 * is the <em>one</em> parser both desktops share: the native-3D
 * {@code PresentoireDocument}/{@code Slide} render its slides into
 * {@code Component3D} nodes, and the plain-Swing {@code PresentoirePanel} paints
 * the very same slides with Java 2D. Neither renderer reads the XML.</p>
 *
 * <p>The 2006 original welded parsing to scene-graph construction, logged every
 * node with {@code System.out.println}, swallowed errors with
 * {@code printStackTrace} and a "TODO Auto-generated catch block", used a raw
 * {@code Vector} and a raw {@code Class}, and - fatally on a modern JVM - built a
 * JavaScript {@code ScriptEngine} while parsing, so a deck with a
 * {@code language="JavaScript"} transition threw before a single slide appeared
 * (the JDK removed Nashorn in JDK 15). This port parses to plain data, defers any
 * scripting to the 3D transition layer (which degrades gracefully), hardens the
 * XML reader against entity expansion, and reports a malformed deck as a single
 * {@link IOException} instead of a stack trace.</p>
 *
 * <p>Instances that wrap a real ZIP hold an open {@link ZipFile} for image
 * loading and must be {@link #close() closed}; the built-in {@link #demo()} deck
 * holds none and needs no closing.</p>
 */
public final class PresentoireModel implements Closeable {

    /** The name of the XML entry inside a Presentoire ZIP deck. */
    public static final String CONTENT_ENTRY = "content.xml";

    /**
     * Half the width (and height) of a slide in document units. The original
     * slide quad is {@code 0.2 x 0.2} centred on the origin, so a slide spans
     * {@code -0.1 .. 0.1} on both axes; the 2D renderer uses this to scale
     * document units into pixels.
     */
    public static final float SLIDE_HALF = 0.1f;

    /**
     * A small built-in deck (text and bullet slides, no images) shown when no
     * document is supplied, so the app and the panel open on real content rather
     * than an empty frame and the hardcoded {@code /home/debian/...} path of the
     * original {@code FileChooser} is gone. Authored to the bottom-left text
     * anchoring and centred-image anchoring the renderers use, in dark ink that
     * reads on the white slide the 2D viewer paints (and over the 3D glass).
     */
    static final String DEMO_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <document>
              <slide id="title">
                <text width="0.18" height="0.030" x="-0.09" y="0.035" color="#1A1A1A">Project Looking Glass</text>
                <text width="0.18" height="0.016" x="-0.09" y="0.006" color="#2F5C9A">Presentoire &#183; a slide-deck viewer</text>
                <text width="0.18" height="0.013" x="-0.09" y="-0.045" color="#6A6A6A">Ported to JDK 21 by Jean-Francois Landreville</text>
              </slide>
              <slide id="about">
                <text width="0.18" height="0.020" x="-0.09" y="0.055" color="#1A1A1A">What this is</text>
                <ul width="0.17" height="0.09" x="-0.085" y="0.030" color="#333333">
                  <li>Opens Presentoire .zip decks</li>
                  <li>Text, bullet lists and images</li>
                  <li>Runs in the 2D and the 3D desktop</li>
                </ul>
              </slide>
              <slide id="open">
                <text width="0.18" height="0.070" x="-0.09" y="-0.030" color="#1A1A1A">Open a deck&#10;Use the Open button to load&#10;your own Presentoire .zip deck</text>
              </slide>
            </document>
            """;

    private final List<SlideModel> slides;
    private final Map<String, ScriptTransitionModel> scriptTransitions;
    private final ZipFile zip;

    private PresentoireModel(List<SlideModel> slides,
            Map<String, ScriptTransitionModel> scriptTransitions, ZipFile zip) {
        this.slides = Collections.unmodifiableList(slides);
        this.scriptTransitions = Collections.unmodifiableMap(scriptTransitions);
        this.zip = zip;
    }

    /**
     * Opens a Presentoire {@code .zip} deck from the filesystem.
     *
     * @param file the deck archive, which must contain a {@code content.xml}
     * @return the parsed model, holding the ZIP open for image loading
     * @throws IOException if the file is not a readable deck with a parseable
     *         {@code content.xml}
     */
    public static PresentoireModel open(File file) throws IOException {
        ZipFile archive = new ZipFile(file);
        boolean ok = false;
        try {
            ZipEntry entry = archive.getEntry(CONTENT_ENTRY);
            if (entry == null) {
                throw new IOException(
                        "Not a Presentoire deck (no " + CONTENT_ENTRY + "): " + file);
            }
            List<SlideModel> slides = new ArrayList<>();
            Map<String, ScriptTransitionModel> scripts = new LinkedHashMap<>();
            try (InputStream in = archive.getInputStream(entry)) {
                parse(in, slides, scripts);
            }
            PresentoireModel model = new PresentoireModel(slides, scripts, archive);
            ok = true;
            return model;
        } finally {
            if (!ok) {
                closeQuietly(archive);
            }
        }
    }

    /**
     * Parses a deck from a bare {@code content.xml} stream, with no ZIP behind it
     * (so {@link #loadImage(String)} always returns null). Used by the built-in
     * demo and by the headless tests.
     *
     * @param contentXml the {@code content.xml} bytes
     * @return the parsed model
     * @throws IOException if the XML is not a parseable Presentoire document
     */
    public static PresentoireModel open(InputStream contentXml) throws IOException {
        List<SlideModel> slides = new ArrayList<>();
        Map<String, ScriptTransitionModel> scripts = new LinkedHashMap<>();
        parse(contentXml, slides, scripts);
        return new PresentoireModel(slides, scripts, null);
    }

    /**
     * The built-in demo deck. Never throws: on the (impossible, since the XML is a
     * compiled-in constant) parse failure it degrades to an empty model so a
     * caller can still show an empty frame rather than crash on startup.
     *
     * @return a demo model with no ZIP behind it
     */
    public static PresentoireModel demo() {
        try {
            return open(new ByteArrayInputStream(DEMO_XML.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new PresentoireModel(new ArrayList<>(), new LinkedHashMap<>(), null);
        }
    }

    /** The number of slides in the deck. */
    public int getSlideCount() {
        return slides.size();
    }

    /**
     * The slide at {@code index}.
     *
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public SlideModel getSlide(int index) {
        return slides.get(index);
    }

    /** The immutable, document-ordered slides. */
    public List<SlideModel> getSlides() {
        return slides;
    }

    /** The slide ids, in document order (for a slide navigator). */
    public List<String> getSlideIds() {
        List<String> ids = new ArrayList<>(slides.size());
        for (SlideModel slide : slides) {
            ids.add(slide.id());
        }
        return Collections.unmodifiableList(ids);
    }

    /**
     * The document-level script transition with the given id, or null when the
     * deck defines none by that name. Pure data - building and running an actual
     * script engine is the 3D transition layer's job.
     */
    public ScriptTransitionModel getScriptTransition(String id) {
        return (id == null) ? null : scriptTransitions.get(id);
    }

    /** True when this model wraps a real ZIP (so images can be resolved). */
    public boolean hasArchive() {
        return zip != null;
    }

    /**
     * Decodes the image stored in the deck under {@code src}.
     *
     * <p>{@link BufferedImage} decoding is headless-safe (it needs no display),
     * so both renderers and the tests can call this. Returns null - never throws -
     * when there is no archive (the demo), the entry is absent, or the bytes are
     * not a readable image, so one broken picture cannot take down a slide.</p>
     *
     * @param src the ZIP-relative image path named by an {@code <img>}
     * @return the decoded image, or null if it cannot be resolved
     */
    public BufferedImage loadImage(String src) {
        try (InputStream in = openEntry(src)) {
            return (in == null) ? null : ImageIO.read(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Opens the raw bytes of a deck entry (an image, or a {@code .class}
     * transition the 3D {@code TransitionFactory} defines at runtime).
     *
     * <p>Returns null - never throws - when there is no archive (the demo) or the
     * entry is absent. The caller owns and must close a non-null stream.</p>
     *
     * @param name the ZIP-relative entry name
     * @return an open stream, or null if it cannot be resolved
     */
    public InputStream openEntry(String name) {
        if (zip == null || name == null || name.isEmpty()) {
            return null;
        }
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try {
            return zip.getInputStream(entry);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Closes the backing ZIP, if any. Safe to call more than once. */
    @Override
    public void close() {
        closeQuietly(zip);
    }

    private static void closeQuietly(ZipFile archive) {
        if (archive != null) {
            try {
                archive.close();
            } catch (IOException ignored) {
                // nothing useful to do while closing
            }
        }
    }

    // ------------------------------------------------------------------
    // XML parsing
    // ------------------------------------------------------------------

    private static void parse(InputStream in, List<SlideModel> slides,
            Map<String, ScriptTransitionModel> scripts) throws IOException {
        try {
            Document document = newSecureFactory().newDocumentBuilder().parse(in);
            Node root = document.getDocumentElement();
            if (root == null || !"document".equals(root.getNodeName())) {
                throw new IOException("Not a Presentoire document (no <document> root)");
            }
            for (Node child = root.getFirstChild(); child != null;
                    child = child.getNextSibling()) {
                if (child.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                String name = child.getNodeName();
                if ("slide".equals(name)) {
                    slides.add(parseSlide(child));
                } else if ("transition".equals(name)) {
                    ScriptTransitionModel script = parseScriptTransition(child);
                    if (script != null) {
                        scripts.put(script.id(), script);
                    }
                }
            }
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("Not a valid Presentoire document: " + e.getMessage(), e);
        }
    }

    /**
     * A document builder hardened against XXE: a deck is untrusted input, so
     * doctypes and external entities are refused. Each feature is set defensively
     * because not every parser supports every flag.
     */
    private static DocumentBuilderFactory newSecureFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        trySet(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySet(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySet(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        trySet(factory, "http://xml.org/sax/features/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setNamespaceAware(false);
        return factory;
    }

    private static void trySet(DocumentBuilderFactory factory, String feature,
            boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | UnsupportedOperationException e) {
            // the parser does not support this hardening flag; carry on
        }
    }

    private static SlideModel parseSlide(Node node) {
        NamedNodeMap attrs = node.getAttributes();
        String id = strAttr(attrs, "id", "");
        String transition = strAttr(attrs, "transition", "");
        List<SlideElement> elements = new ArrayList<>();
        for (Node child = node.getFirstChild(); child != null;
                child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            SlideElement element = parseElement(child);
            if (element != null) {
                elements.add(element);
            }
        }
        return new SlideModel(id, transition, elements);
    }

    private static ScriptTransitionModel parseScriptTransition(Node node) {
        NamedNodeMap attrs = node.getAttributes();
        String id = strAttr(attrs, "id", "");
        if (id.isEmpty()) {
            return null;
        }
        String language = strAttr(attrs, "language", "");
        String code = node.getTextContent();
        return new ScriptTransitionModel(id, language, code);
    }

    /** Parses one {@code <text>}, {@code <img>} or {@code <ul>}; null if unknown. */
    private static SlideElement parseElement(Node node) {
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) {
            return null;
        }
        float x = floatAttr(attrs, "x", 0f);
        float y = floatAttr(attrs, "y", 0f);
        float z = floatAttr(attrs, "z", SlideElement.DEFAULT_Z);
        float width = floatAttr(attrs, "width", 0f);
        float height = floatAttr(attrs, "height", 0f);
        switch (node.getNodeName()) {
            case "text": {
                int argb = ModelColor.parse(strAttr(attrs, "color", "black"));
                return new SlideElement.Text(x, y, z, width, height, argb,
                        node.getTextContent());
            }
            case "img": {
                return new SlideElement.Image(x, y, z, width, height,
                        strAttr(attrs, "src", ""));
            }
            case "ul": {
                int argb = ModelColor.parse(strAttr(attrs, "color", "black"));
                List<String> items = new ArrayList<>();
                for (Node child = node.getFirstChild(); child != null;
                        child = child.getNextSibling()) {
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "li".equals(child.getNodeName())) {
                        items.add(child.getTextContent());
                    }
                }
                return new SlideElement.ListElement(x, y, z, width, height, argb, items);
            }
            default:
                return null;
        }
    }

    private static float floatAttr(NamedNodeMap attrs, String name, float fallback) {
        if (attrs == null) {
            return fallback;
        }
        Node node = attrs.getNamedItem(name);
        if (node == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(node.getNodeValue().trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    private static String strAttr(NamedNodeMap attrs, String name, String fallback) {
        if (attrs == null) {
            return fallback;
        }
        Node node = attrs.getNamedItem(name);
        return (node == null) ? fallback : node.getNodeValue();
    }
}
