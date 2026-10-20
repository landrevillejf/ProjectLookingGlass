/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (assetmgr): reads a cell file whose type is not ported.
 *
 * JAXB is a strict reader: it can tolerate an unknown CHILD element (the tolerant
 * event handler the world loader installs skips those) but an unknown ROOT is not
 * a recoverable error -- there is no class to hand the document to, so unmarshal
 * throws and the caller sees nothing at all. Every stock world has such roots.
 * The combined world writes nine of its forty-one cells that way -- four posters,
 * two PDF viewers, a whiteboard, a remote window, the environment -- and each one
 * used to vanish from the loaded world without a trace, which is how a world with
 * plenty in it ended up rendering as an empty field.
 *
 * This reader is the second path, taken only when JAXB's first one fails. It does
 * not try to know the cell type. It reads the three things every cell in the
 * format has, whatever its type: the name the author gave it, the placement its
 * position component records, and the asset it displays (the element named
 * {@code *-uri}, which is how a poster says which picture to show). Those three
 * are enough to put the object in the world at the right place and, for the ones
 * that are pictures, to draw it -- see {@link ForeignCellServerState}.
 *
 * The placement is read rather than unmarshalled, and matches the adapters
 * wonderland-common installs for the JAXB path exactly: translation and scale are
 * {@code <x/><y/><z/>}, rotation is an axis and an angle, bounds carry a type.
 * Doing it here instead of round-tripping the fragment through JAXB keeps this
 * path free of the very mechanism that failed on the file, and keeps it testable
 * with nothing but a string.
 */
package org.jdesktop.wonderland.modules.asset;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jdesktop.wonderland.common.cell.state.ForeignCellServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Recovers name, placement and asset from a cell file JAXB refuses to open. */
public final class ForeignCellReader {

    /**
     * The part every cell root has in it, in the 2006 format and in this reader's
     * trust model. Matched as {@code contains} rather than {@code endsWith}
     * because the whiteboard is written {@code <whiteboard-cell-svg>}: the type
     * suffix comes first and the media type second, and a reader that insisted the
     * root <em>end</em> in {@code -cell} silently dropped every whiteboard in the
     * shipped worlds -- nine of the combined world's forty-one objects, and the
     * whole point of this class is that no object is dropped for being unread.
     */
    private static final String CELL_MARKER = "-cell";

    /** The suffix that marks an element as "the asset this cell displays". */
    private static final String URI_SUFFIX = "uri";

    /** What makes a string a reference rather than an element's own name. */
    private static final java.util.regex.Pattern SCHEME =
            java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:");

    /**
     * A factory that parses a document and cannot reach the filesystem or the
     * network. World files are shipped inside jars pulled from a directory an
     * operator dropped them in, so they are input, and input that can name an
     * external entity is input that can read whatever the server's user can.
     */
    private static final DocumentBuilderFactory FACTORY =
            sealedFactory(DocumentBuilderFactory.newInstance());

    /**
     * Lock the given factory down against doctypes and external entities. Takes
     * the factory as a parameter so a test can hand in one whose {@code setFeature}
     * refuses, driving the degrade below on demand rather than depending on a
     * third-party parser happening to lack a feature string.
     */
    static DocumentBuilderFactory sealedFactory(DocumentBuilderFactory factory) {
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
        } catch (javax.xml.parsers.ParserConfigurationException ex) {
            // A parser without these switches is not the JDK's; the settings that
            // did apply still hold, and a world file is not worth failing to read
            // over one feature string a third-party parser does not know.
        }
        return factory;
    }

    /**
     * Read one cell file.
     *
     * @param xml the file's text
     * @return the recovered state, or {@code null} when there is nothing to
     *         recover: unreadable XML, a root that is not a cell, or a cell with
     *         no name. A cell this cannot place is still a cell the world has, so
     *         the caller drops only the ones with no identity at all.
     */
    public static ForeignCellServerState read(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        Element root = parse(xml);
        if (root == null || !isCell(root)) {
            return null;
        }
        String name = text(root, "name");
        // text() returns either null or a non-blank string, so the only thing to
        // test here is presence -- a blank name can never reach this point.
        if (name == null) {
            return null;
        }
        ForeignCellServerState state = new ForeignCellServerState();
        state.setName(name.trim());
        state.setCellType(root.getTagName());
        state.setAssetURI(assetUri(root));
        state.setPixelScaleX(number(root, "pixelScaleX",
                ForeignCellServerState.DEFAULT_PIXEL_SCALE));
        state.setPixelScaleY(number(root, "pixelScaleY",
                ForeignCellServerState.DEFAULT_PIXEL_SCALE));
        state.setPixelSize(number(root, "preferredWidth", 0.0),
                number(root, "preferredHeight", 0.0));
        PositionComponentServerState position = positionOf(root);
        if (position != null) {
            state.addComponentServerState(position);
        }
        return state;
    }

    /** The document's root element, or null when the text is not well-formed XML. */
    private static Element parse(String xml) {
        try {
            DocumentBuilder builder = FACTORY.newDocumentBuilder();
            // The world files begin with an XML declaration; a byte-order mark at
            // the very front is the one thing that makes a well-formed file look
            // like "content is not allowed in prolog", so it is trimmed here.
            Document doc = builder.parse(new InputSource(
                    new StringReader(stripBom(xml))));
            return doc.getDocumentElement();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String stripBom(String xml) {
        return xml.startsWith("\uFEFF") ? xml.substring(1) : xml;
    }

    /**
     * Whether this root is a cell. Checked rather than assumed because the same
     * store holds {@code module.xml} and {@code requires.xml}, and a reader that
     * called any document with a {@code <name>} a world cell would invent objects
     * out of the module's own metadata.
     */
    private static boolean isCell(Element root) {
        return root.getTagName().contains(CELL_MARKER);
    }

    /** The first direct child element's text, or null when absent or empty. */
    private static String text(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                // getTextContent() on an element node is never null (the DOM returns
                // the concatenation of its text, "" when there is none), so only the
                // blank test is live here.
                String value = node.getTextContent();
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * The asset the cell displays: the first element below the root whose name ends
     * in "uri" and whose text is an actual reference. Searched by suffix rather
     * than by the handful of names known today because the format never agreed on
     * one: a poster writes {@code image-uri}, a document viewer writes
     * {@code documentURI} -- capital UR-I, so the match is case-insensitive -- and
     * a world written by a module this port has never met will write something else
     * again. The scheme test is what keeps two other kinds of element out: a
     * {@code <shared-state-component>} carries a {@code <key>document-uri</key>}
     * whose text is the literal words "document-uri", and a whiteboard carries its
     * {@code <svgDocumentURI>} as the drawing itself -- markup that mentions
     * {@code http://www.w3.org/2000/svg} in passing and is not a file anybody can
     * fetch. An entry name and a document are not references; a reference is a
     * value that <em>begins</em> with a scheme. The shape worth keeping is "this
     * cell shows this file".
     */
    private static String assetUri(Element root) {
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (!element.getTagName().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(URI_SUFFIX)) {
                continue;
            }
            // getTextContent() on an element node is never null, so the value goes
            // straight to the trim-and-scheme test.
            String value = element.getTextContent();
            String trimmed = value.trim();
            if (SCHEME.matcher(trimmed).find()) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * The cell's placement, or null when the file records none. A cell with no
     * position component is legitimately at the origin with no turn and no
     * stretch, so this returns the default state rather than failing; it returns
     * null only if the element is missing entirely, which the caller reads as
     * "nowhere" and the REST surface already omits from its descriptors.
     */
    private static PositionComponentServerState positionOf(Element root) {
        NodeList components = root.getElementsByTagName("position-component");
        if (components.getLength() == 0) {
            return null;
        }
        Element position = (Element) components.item(0);
        PositionComponentServerState state = new PositionComponentServerState();
        Vector3f translation = vector(position, "translation");
        if (translation != null) {
            state.setTranslation(translation);
        }
        Vector3f scale = vector(position, "scale");
        if (scale != null) {
            state.setScaling(scale);
        }
        Quaternion rotation = rotation(position);
        if (rotation != null) {
            state.setRotation(rotation);
        }
        PositionComponentServerState.Bounds bounds = bounds(position);
        if (bounds != null) {
            state.setBounds(bounds);
        }
        return state;
    }

    /** An {@code <x/><y/><z/>} block, or null when absent or unreadable. */
    private static Vector3f vector(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return null;
        }
        Element element = (Element) list.item(0);
        return new Vector3f(number(element, "x"), number(element, "y"),
                number(element, "z"));
    }

    /**
     * The {@code <rotation>} block as the adapter reads it: an axis and an angle
     * about that axis. An all-zero axis is no rotation -- {@code fromAngleAxis}
     * would divide by its length and produce a quaternion of NaNs, which Java 3D
     * then refuses at attach time and takes the whole branch down with it.
     */
    private static Quaternion rotation(Element parent) {
        NodeList list = parent.getElementsByTagName("rotation");
        if (list.getLength() == 0) {
            return null;
        }
        Element element = (Element) list.item(0);
        float x = number(element, "x");
        float y = number(element, "y");
        float z = number(element, "z");
        float angle = number(element, "angle");
        if (x == 0f && y == 0f && z == 0f) {
            return new Quaternion();
        }
        Quaternion rotation = new Quaternion();
        rotation.fromAngleAxis(angle, new Vector3f(x, y, z));
        return rotation;
    }

    /** The authored footprint, or null when the file records none. */
    private static PositionComponentServerState.Bounds bounds(Element parent) {
        NodeList list = parent.getElementsByTagName("bounds");
        if (list.getLength() == 0) {
            return null;
        }
        Element element = (Element) list.item(0);
        PositionComponentServerState.Bounds bounds =
                new PositionComponentServerState.Bounds();
        String type = text(element, "type");
        if (type != null) {
            try {
                bounds.type = PositionComponentServerState.Bounds.BoundsType
                        .valueOf(type.trim());
            } catch (IllegalArgumentException ex) {
                // An unknown hint shape is still a place: keep the default rather
                // than lose the cell's size over a word the format outgrew.
            }
        }
        bounds.x = number(element, "x");
        bounds.y = number(element, "y");
        bounds.z = number(element, "z");
        return bounds;
    }

    /** A child element read as a number, 0 when absent or malformed. */
    private static float number(Element parent, String tag) {
        return (float) number(parent, tag, 0.0);
    }

    /**
     * A direct child element read as a number, {@code fallback} when absent, not
     * finite or malformed. The finite test is not pedantry: {@code Double.parseDouble}
     * accepts the words "NaN" and "Infinity", and either reaching a scale turns a
     * picture into a degenerate quad that Java 3D refuses at attach time -- one bad
     * number that costs the world every object in it.
     */
    private static double number(Element parent, String tag, double fallback) {
        String value = text(parent, tag);
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private ForeignCellReader() {
    }
}
