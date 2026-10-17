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
 * holds none and needs no closing. A model built by {@link #of} from an imported
 * PowerPoint / LibreOffice deck (or synthesized in memory) carries its pictures
 * as an in-memory {@code src -> bytes} map instead of a ZIP, so it too needs no
 * closing and {@link #loadImage(String)} resolves from that map.</p>
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
     * The built-in default deck: a complete, text-and-vector-only tour of
     * Project Looking Glass - its 3D and 2D desktops, the shared-model pattern
     * they both rest on, the applications portfolio, the toolchain and this
     * port's attribution. It is shown when no document is supplied, so the app
     * and the panel open on real content rather than an empty frame (and the
     * hardcoded {@code /home/debian/...} path of the original {@code FileChooser}
     * is gone).
     *
     * <p>It stays a compiled-in constant - no binary images, no Git LFS - so it
     * is diff-reviewable, headless-testable and exportable to any format the
     * codecs support. It is authored to the bottom-left text anchoring and
     * centred-image anchoring the renderers use, in dark ink that reads on the
     * white slide the 2D viewer paints (and over the 3D glass).</p>
     */
    static final String LOOKING_GLASS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <document>
              <slide id="title">
                <text width="0.18" height="0.030" x="-0.09" y="0.028" color="#1A1A1A">Project Looking Glass</text>
                <text width="0.18" height="0.015" x="-0.09" y="0.002" color="#2F5C9A">The 3D and 2D desktop, modernized for JDK 21</text>
                <text width="0.18" height="0.012" x="-0.09" y="-0.060" color="#6A6A6A">Presentoire &#183; the built-in deck</text>
              </slide>
              <slide id="what">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">What is Looking Glass</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>An experimental desktop from Sun Microsystems (2004-2006)</li>
                  <li>A 3D scene-graph desktop and a plain 2D/Swing desktop</li>
                  <li>Resurrected for today: Gradle 8.14, JDK 21, Java 3D on Jogamp</li>
                  <li>One shared, AWT-free model drives both desktops</li>
                </ul>
              </slide>
              <slide id="two-desktops">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">One project, two desktops</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>3D: windows live as textured quads in a scene graph</li>
                  <li>2D: a classic Swing MDI desktop, for machines without 3D</li>
                  <li>Applications are written once and shown in either</li>
                  <li>The 2D desktop never loads Java 3D</li>
                </ul>
              </slide>
              <slide id="3d-scenegraph">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">The 3D desktop: scene graph</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>Frame3D hosts the canvas; Component3D embeds live Swing</li>
                  <li>Windows are Texture2D quads you can move, scale and spin</li>
                  <li>Java 3D retained mode, on the Jogamp fork (1.7.2)</li>
                  <li>Packages org.jogamp.java3d and org.jogamp.vecmath</li>
                </ul>
              </slide>
              <slide id="3d-windowing">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">The 3D desktop: windowing</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>A wonderland-style scene of floating, translucent panels</li>
                  <li>An X11 compositor path captures real windows to textures</li>
                  <li>Under Wayland a host capture may read black - not a defect</li>
                  <li>The start menu launches applications in-JVM</li>
                </ul>
              </slide>
              <slide id="2d-mdi">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">The 2D/Swing desktop</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>A multiple-document interface on a JDesktopPane</li>
                  <li>Internal frames, no Java 3D, no native compositor</li>
                  <li>Runs on modest hardware and needs no display server quirks</li>
                  <li>The same applications, presented as Swing panels</li>
                </ul>
              </slide>
              <slide id="2d-hosting">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">Hosting panels in 2D</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>Applications expose a plain Swing panel for the 2D desktop</li>
                  <li>SwingNode bridges a JavaFX panel where an app needs one</li>
                  <li>Desktop2DAppRegistry.PANEL_APPS lists the hosted panels</li>
                  <li>The 3D desktop wraps the same panel in a Component3D</li>
                </ul>
              </slide>
              <slide id="shared-model">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">The shared-model pattern</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>Parse once into an AWT-free, Java 3D-free model</li>
                  <li>Two renderers read the very same immutable data</li>
                  <li>3D builds Component3D nodes; 2D paints with Java 2D</li>
                  <li>Testable headlessly, with no display or scene graph</li>
                </ul>
              </slide>
              <slide id="ex-presentoire">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">Worked example: Presentoire</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>PresentoireModel is the single shared parser</li>
                  <li>SlideRenderer paints slides; Slide builds 3D quads</li>
                  <li>Now opens and saves .pptx, .ppt and .odp too</li>
                  <li>This deck is its built-in default</li>
                </ul>
              </slide>
              <slide id="ex-chess">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">Worked example: Chess</text>
                <ul width="0.175" height="0.09" x="-0.085" y="0.012" color="#333333">
                  <li>One board model, two views</li>
                  <li>3D pieces in the scene graph; a 2D board on a panel</li>
                  <li>Move validation lives in the model, not the view</li>
                </ul>
              </slide>
              <slide id="ex-physics">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">Worked example: Physics</text>
                <ul width="0.175" height="0.09" x="-0.085" y="0.012" color="#333333">
                  <li>A Newton's cradle, rebuilt for JDK 21</li>
                  <li>One simulation model; the 3D and 2D faces both render it</li>
                  <li>The physics loop is independent of any renderer</li>
                </ul>
              </slide>
              <slide id="apps">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">The applications portfolio</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>Presentoire, Chess, Physics, PDF Viewer, Archive</li>
                  <li>Mail, FTP client, Web Browser, Help Center</li>
                  <li>DB Manager, Update Manager, WonderLAN softphone</li>
                  <li>Each ships a 2D panel and, where it fits, a 3D face</li>
                </ul>
              </slide>
              <slide id="mandela">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">Project Mandela</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>The build's own scripting and release tooling</li>
                  <li>Repository checks, a start-menu audit, what's-new pages</li>
                  <li>Cucumber-driven acceptance scenarios</li>
                  <li>Guards the gates every change must pass</li>
                </ul>
              </slide>
              <slide id="toolchain">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">Build and toolchain</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>Gradle 8.14 with a version catalog (libs.versions.toml)</li>
                  <li>A JDK 21 toolchain; Jogamp natives resolved per platform</li>
                  <li>Modules: lg3d-core, lg3d-apps, lg3d-incubator, lg3d-mandela</li>
                  <li>Headless tests run with no display attached</li>
                </ul>
              </slide>
              <slide id="formats">
                <text width="0.18" height="0.022" x="-0.09" y="0.055" color="#1A1A1A">Presentoire: many formats</text>
                <ul width="0.175" height="0.10" x="-0.085" y="0.012" color="#333333">
                  <li>Native .zip, plus PowerPoint .pptx and legacy .ppt (Apache POI)</li>
                  <li>LibreOffice Impress .odp, parsed in pure Java</li>
                  <li>Open and Save As, right from the 2D panel</li>
                  <li>The format is sniffed from content, not the extension</li>
                </ul>
              </slide>
              <slide id="credits">
                <text width="0.18" height="0.022" x="-0.09" y="0.058" color="#1A1A1A">Credits and attribution</text>
                <text width="0.18" height="0.014" x="-0.09" y="0.032" color="#2F5C9A">Ported to JDK 21 by Jean-Francois Landreville</text>
                <ul width="0.175" height="0.085" x="-0.085" y="0.005" color="#333333">
                  <li>Original Project Looking Glass, Sun Microsystems (2004-2006)</li>
                  <li>Presentoire slide viewer by Pierre Ducroquet</li>
                  <li>Gradle / JDK 21 modernization and these improvements:</li>
                  <li>Jean-Francois Landreville</li>
                </ul>
              </slide>
            </document>
            """;

    private final List<SlideModel> slides;
    private final Map<String, ScriptTransitionModel> scriptTransitions;
    private final ZipFile zip;
    private final Map<String, byte[]> inlineImages;

    private PresentoireModel(List<SlideModel> slides,
            Map<String, ScriptTransitionModel> scriptTransitions, ZipFile zip,
            Map<String, byte[]> inlineImages) {
        this.slides = Collections.unmodifiableList(slides);
        this.scriptTransitions = Collections.unmodifiableMap(scriptTransitions);
        this.zip = zip;
        this.inlineImages = (inlineImages == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inlineImages));
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
            PresentoireModel model = new PresentoireModel(slides, scripts, archive, null);
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
        return new PresentoireModel(slides, scripts, null, null);
    }

    /**
     * Builds an in-memory model from already-parsed slides, with no ZIP behind it:
     * the pictures an {@code <img>} names are carried in {@code images} as a
     * {@code src -> bytes} map. This is how the format codecs hand an imported
     * PowerPoint / LibreOffice deck - or a freshly synthesized one - to the same
     * renderers a native {@code .zip} deck reaches, and why {@link #loadImage}
     * and {@link #openEntry} resolve from the map as well as the archive.
     *
     * @param slides           the document-ordered slides (copied)
     * @param scriptTransitions the document-level script transitions, may be null
     * @param images           the {@code src -> bytes} pictures, may be null
     * @return the in-memory model; it holds no ZIP, so {@link #close()} is a no-op
     */
    public static PresentoireModel of(List<SlideModel> slides,
            Map<String, ScriptTransitionModel> scriptTransitions,
            Map<String, byte[]> images) {
        return new PresentoireModel(
                new ArrayList<>(slides),
                (scriptTransitions == null)
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(scriptTransitions),
                null,
                images);
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
            return open(new ByteArrayInputStream(
                    LOOKING_GLASS_XML.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new PresentoireModel(new ArrayList<>(), new LinkedHashMap<>(), null, null);
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
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (zip != null) {
            ZipEntry entry = zip.getEntry(name);
            if (entry != null) {
                try {
                    return zip.getInputStream(entry);
                } catch (IOException | RuntimeException e) {
                    // fall through to the in-memory images, then to null
                }
            }
        }
        byte[] inline = inlineImages.get(name);
        return (inline == null) ? null : new ByteArrayInputStream(inline);
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
