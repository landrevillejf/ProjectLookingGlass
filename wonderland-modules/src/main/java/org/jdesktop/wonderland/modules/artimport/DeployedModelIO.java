/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5b (artimport): readers for the .dep / .ldr bundle files.
 *
 * Small namespace-agnostic DOM readers turning the two XML descriptors the 2006
 * artimport wrote into {@link DeployedModel} and {@link LoaderData}. Like the
 * Collada parser these are hardened against DTD / external entities and never
 * touch the scene graph; they fail loudly (DeployedModelException) on malformed
 * XML so the asset manager can record an ERROR load status instead of half-loading.
 */
package org.jdesktop.wonderland.modules.artimport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jogamp.vecmath.Vector3f;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Parses the deployed-model {@code .dep} and loader-data {@code .ldr} documents. */
public final class DeployedModelIO {

    /** Parse a {@code .dep} deployed-model from a UTF-8 string. */
    public DeployedModel parseDeployedModel(String xml) {
        return parseDeployedModel(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Parse a {@code .dep} deployed-model from a stream (never closed here). */
    public DeployedModel parseDeployedModel(InputStream in) {
        Element root = document(in).getDocumentElement();
        Element rotationElement = first(root, "modelBGRotation");
        return new DeployedModel(
                intText(first(root, "version")),
                textOr(first(root, "modelURL")),
                textOr(first(root, "loaderDataURL")),
                xyz(first(root, "modelBGScale")),
                xyz(first(root, "modelBGTranslation")),
                xyz(rotationElement),
                rotationElement == null ? 0f
                        : floatText(first(rotationElement, "angle")),
                parseBounds(first(root, "modelBounds")),
                textOr(first(root, "modelLoaderClassname")));
    }

    /** Parse a {@code .ldr} loader-data from a UTF-8 string. */
    public LoaderData parseLoaderData(String xml) {
        return parseLoaderData(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Parse a {@code .ldr} loader-data from a stream (never closed here). */
    public LoaderData parseLoaderData(InputStream in) {
        Element root = document(in).getDocumentElement();
        List<DeployedTexture> textures = new ArrayList<>();
        for (Element tex : children(root, "deployedTextures")) {
            textures.add(new DeployedTexture(attr(tex, "value"), attr(tex, "key")));
        }
        return new LoaderData(
                intText(first(root, "version")),
                textOr(first(root, "modelLoaderClassname")),
                textures);
    }

    private static DeployedModel.Bounds parseBounds(Element bounds) {
        if (bounds == null) {
            return null;
        }
        Vector3f center = new Vector3f(
                floatText(first(bounds, "center_x")),
                floatText(first(bounds, "center_y")),
                floatText(first(bounds, "center_z")));
        Vector3f dimension = new Vector3f(
                floatText(first(bounds, "dimension_x")),
                floatText(first(bounds, "dimension_y")),
                floatText(first(bounds, "dimension_z")));
        return new DeployedModel.Bounds(textOr(first(bounds, "boundsType")), center, dimension);
    }

    /** Read a {@code <x><y><z>} triple; a null block (or absent axis) reads as 0. */
    private static Vector3f xyz(Element parent) {
        if (parent == null) {
            return null;
        }
        return new Vector3f(
                floatText(first(parent, "x")),
                floatText(first(parent, "y")),
                floatText(first(parent, "z")));
    }

    /**
     * Strip a leading {@code ./} so a loader-data texture path matches the Collada
     * {@code <image>} init_from key. null in, null out.
     */
    public static String normalizeRelative(String path) {
        if (path == null) {
            return null;
        }
        return path.startsWith("./") ? path.substring(2) : path;
    }

    // ---- plumbing -------------------------------------------------------------

    private static Document document(InputStream in) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(in);
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new DeployedModelException("malformed deployed-model xml: " + ex.getMessage(), ex);
        }
    }

    private static List<Element> elements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node node = kids.item(i);
            if (node instanceof Element) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Element e : elements(parent)) {
            if (localName.equals(local(e))) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element first(Element parent, String localName) {
        for (Element e : elements(parent)) {
            if (localName.equals(local(e))) {
                return e;
            }
        }
        return null;
    }

    private static String local(Element e) {
        // document() always sets setNamespaceAware(true), so getLocalName() is never
        // null for a parsed element; the tag-name / prefix fallback this once carried
        // was reachable only under a non-namespace-aware parser that does not exist
        // here.
        return e.getLocalName();
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private static String text(Element e) {
        return e == null ? "" : e.getTextContent().trim();
    }

    private static String textOr(Element e) {
        String t = text(e);
        return t.isEmpty() ? null : t;
    }

    private static int intText(Element e) {
        String t = text(e);
        try {
            return t.isEmpty() ? 0 : Integer.parseInt(t);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static float floatText(Element e) {
        String t = text(e);
        try {
            return t.isEmpty() ? 0f : Float.parseFloat(t);
        } catch (NumberFormatException ex) {
            return 0f;
        }
    }

    /** A failure to parse a deployed-model / loader-data document. */
    public static final class DeployedModelException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DeployedModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
