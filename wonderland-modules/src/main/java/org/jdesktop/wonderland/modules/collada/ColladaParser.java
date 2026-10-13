/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): the COLLADA 1.4.1 DOM parser.
 *
 * Reads the subset the shipped Wonderland models actually use -- asset, the
 * geometry/scene/material/effect/image libraries, node transforms and the
 * instance_geometry / instance_material bindings -- into the {@link ColladaDocument}
 * model. It is namespace-agnostic (matches on the element's local name) and
 * DTD/external-entity hardened, because the authoring tools emit the COLLADA
 * namespace but some exports omit it. It deliberately stops at the data model:
 * turning a ColladaDocument into Java 3D Shape3D/GeometryArray is the deferred M6
 * renderer (the 2006 jmecolladaloader half), so no scene-graph import happens here.
 */
package org.jdesktop.wonderland.modules.collada;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Parses COLLADA XML into a {@link ColladaDocument}. */
public final class ColladaParser {

    private static final List<String> PRIMITIVES =
            List.of("triangles", "polylist", "lines", "linestrips", "polygons", "tristrips", "trifans");
    private static final List<String> TECHNIQUES =
            List.of("lambert", "phong", "blinn", "constant");

    /** Parse a document from a UTF-8 string. */
    public ColladaDocument parse(String xml) {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Parse a document from a stream (the caller keeps ownership; this never closes it).
     *
     * @throws ColladaParseException when the bytes are not well-formed XML
     */
    public ColladaDocument parse(InputStream in) {
        Document doc;
        try {
            doc = newSecureBuilder().parse(in);
        } catch (SAXException | IOException ex) {
            throw new ColladaParseException("not well-formed COLLADA: " + ex.getMessage(), ex);
        }
        Element root = doc.getDocumentElement();
        return new ColladaDocument(
                parseAsset(first(root, "asset")),
                parseGeometries(root),
                parseMaterials(root),
                parseEffects(root),
                parseImages(root),
                parseVisualScenes(root),
                parseRootSceneId(root));
    }

    private static DocumentBuilder newSecureBuilder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("no secure DOM parser available", ex);
        }
    }

    // ---- asset -----------------------------------------------------------------

    private static ColladaAsset parseAsset(Element asset) {
        if (asset == null) {
            return null;
        }
        double meter = 1.0;
        Element unit = first(asset, "unit");
        if (unit != null) {
            meter = attr(unit, "meter", 1.0);
        }
        String tool = null;
        Element contributor = first(asset, "contributor");
        if (contributor != null) {
            Element toolEl = first(contributor, "authoring_tool");
            tool = toolEl == null ? null : text(toolEl);
        }
        Element upAxis = first(asset, "up_axis");
        return new ColladaAsset(
                upAxis == null ? null : text(upAxis),
                meter, tool,
                textOr(first(asset, "created")),
                textOr(first(asset, "modified")));
    }

    // ---- geometries -----------------------------------------------------------

    private static Map<String, ColladaGeometry> parseGeometries(Element root) {
        Map<String, ColladaGeometry> out = new LinkedHashMap<>();
        for (Element lib : all(root, "library_geometries")) {
            for (Element geom : children(lib, "geometry")) {
                Element mesh = first(geom, "mesh");
                out.put(attr(geom, "id"), new ColladaGeometry(
                        attr(geom, "id"), attr(geom, "name"), parseMesh(mesh)));
            }
        }
        return out;
    }

    private static ColladaGeometry.Mesh parseMesh(Element mesh) {
        if (mesh == null) {
            return new ColladaGeometry.Mesh(List.of(), null, List.of());
        }
        List<ColladaGeometry.Source> sources = new ArrayList<>();
        for (Element src : children(mesh, "source")) {
            sources.add(parseSource(src));
        }
        ColladaGeometry.Vertices vertices = null;
        Element verts = first(mesh, "vertices");
        if (verts != null) {
            vertices = new ColladaGeometry.Vertices(attr(verts, "id"), parseInputs(verts));
        }
        List<ColladaGeometry.Primitive> prims = new ArrayList<>();
        for (Element child : elements(mesh)) {
            if (PRIMITIVES.contains(local(child))) {
                prims.add(parsePrimitive(child));
            }
        }
        return new ColladaGeometry.Mesh(sources, vertices, prims);
    }

    private static ColladaGeometry.Source parseSource(Element src) {
        double[] data = new double[0];
        Element floatArray = first(src, "float_array");
        if (floatArray != null) {
            data = parseDoubles(text(floatArray));
        }
        int stride = 1;
        long count = 0;
        Element accessor = first(src, "accessor");
        if (accessor != null) {
            stride = attr(accessor, "stride", 1);
            Element countEl = first(accessor, "count");
            count = countEl == null ? 0 : parseLong(text(countEl), 0);
        }
        return new ColladaGeometry.Source(attr(src, "id"), data, stride, count);
    }

    private static ColladaGeometry.Primitive parsePrimitive(Element prim) {
        long count = attr(prim, "count", 0);
        List<ColladaGeometry.Input> inputs = parseInputs(prim);
        long[] indices = parseLongs(textOfFirst(prim, "p"));
        return new ColladaGeometry.Primitive(
                local(prim), attr(prim, "material"), count, inputs, indices);
    }

    private static List<ColladaGeometry.Input> parseInputs(Element parent) {
        List<ColladaGeometry.Input> inputs = new ArrayList<>();
        for (Element in : children(parent, "input")) {
            inputs.add(new ColladaGeometry.Input(
                    attr(in, "semantic"), attr(in, "source"),
                    attr(in, "offset", 0), attr(in, "set")));
        }
        return inputs;
    }

    // ---- nodes / scenes -------------------------------------------------------

    private static Map<String, ColladaVisualScene> parseVisualScenes(Element root) {
        Map<String, ColladaVisualScene> out = new LinkedHashMap<>();
        for (Element lib : all(root, "library_visual_scenes")) {
            for (Element scene : children(lib, "visual_scene")) {
                List<ColladaNode> nodes = new ArrayList<>();
                for (Element node : children(scene, "node")) {
                    nodes.add(parseNode(node));
                }
                out.put(attr(scene, "id"),
                        new ColladaVisualScene(attr(scene, "id"), attr(scene, "name"), nodes));
            }
        }
        return out;
    }

    private static ColladaNode parseNode(Element node) {
        List<ColladaTransform> transforms = new ArrayList<>();
        List<ColladaNode> children = new ArrayList<>();
        List<ColladaNode.InstanceGeometry> geometries = new ArrayList<>();
        List<String> nodeInstances = new ArrayList<>();
        for (Element child : elements(node)) {
            String name = local(child);
            ColladaTransform.Kind kind = transformKind(name);
            if (kind != null) {
                transforms.add(new ColladaTransform(kind, attr(child, "sid"), parseDoubles(text(child))));
            } else if ("node".equals(name)) {
                children.add(parseNode(child));
            } else if ("instance_geometry".equals(name)) {
                geometries.add(parseInstanceGeometry(child));
            } else if ("instance_node".equals(name)) {
                nodeInstances.add(stripHash(attr(child, "url")));
            }
        }
        return new ColladaNode(attr(node, "id"), attr(node, "name"), attr(node, "type"),
                transforms, children, geometries, nodeInstances);
    }

    private static ColladaNode.InstanceGeometry parseInstanceGeometry(Element inst) {
        List<ColladaNode.InstanceMaterial> materials = new ArrayList<>();
        Element bind = first(inst, "bind_material");
        if (bind != null) {
            Element tech = first(bind, "technique_common");
            if (tech != null) {
                for (Element im : children(tech, "instance_material")) {
                    List<String> texcoords = new ArrayList<>();
                    Element bvt = first(im, "bind_vertex_input");
                    if (bvt != null) {
                        String tc = attr(bvt, "input_semantic");
                        if (tc != null) {
                            texcoords.add(tc);
                        }
                    }
                    materials.add(new ColladaNode.InstanceMaterial(
                            attr(im, "symbol"), stripHash(attr(im, "target")), texcoords));
                }
            }
        }
        return new ColladaNode.InstanceGeometry(stripHash(attr(inst, "url")), materials);
    }

    private static String parseRootSceneId(Element root) {
        Element scene = first(root, "scene");
        if (scene == null) {
            return null;
        }
        Element inst = first(scene, "instance_visual_scene");
        return inst == null ? null : stripHash(attr(inst, "url"));
    }

    // ---- appearance -----------------------------------------------------------

    private static Map<String, ColladaEffect> parseEffects(Element root) {
        Map<String, ColladaEffect> out = new LinkedHashMap<>();
        for (Element lib : all(root, "library_effects")) {
            for (Element effect : children(lib, "effect")) {
                out.put(attr(effect, "id"), parseEffect(effect));
            }
        }
        return out;
    }

    private static ColladaEffect parseEffect(Element effect) {
        String technique = null;
        double[] diffuse = null;
        double transparency = 1.0;
        Map<String, String> samplers = new LinkedHashMap<>();

        Element profile = first(effect, "profile_COMMON");
        if (profile != null) {
            Element techCommon = first(profile, "technique_common");
            if (techCommon == null) {
                // the 2006-era SketchUp / Google Earth exporters emit the common
                // technique as a plain <technique sid="COMMON"> instead
                techCommon = first(profile, "technique");
            }
            Element techniqueEl = null;
            if (techCommon != null) {
                for (Element child : elements(techCommon)) {
                    if (TECHNIQUES.contains(local(child))) {
                        techniqueEl = child;
                        technique = local(child);
                        break;
                    }
                }
            }
            if (techniqueEl != null) {
                Element diffuseEl = first(techniqueEl, "diffuse");
                if (diffuseEl != null) {
                    Element color = first(diffuseEl, "color");
                    if (color != null) {
                        diffuse = parseDoubles(text(color));
                    }
                }
                Element transparentEl = first(techniqueEl, "transparent");
                if (transparentEl != null) {
                    transparency = firstTransparencyFactor(transparentEl);
                }
            }
            // samplers: every <texture> descendant references an image id
            int n = 0;
            for (Element tex : descendants(effect, "texture")) {
                String image = attr(tex, "texture");
                if (image != null) {
                    samplers.put("sampler" + (n++), stripHash(image));
                }
            }
        }
        return new ColladaEffect(attr(effect, "id"), attr(effect, "name"),
                technique, diffuse, transparency, samplers);
    }

    private static double firstTransparencyFactor(Element transparent) {
        Element color = first(transparent, "color");
        if (color != null) {
            double[] c = parseDoubles(text(color));
            if (c.length >= 4) {
                return c[3];
            }
        }
        Element floatEl = first(transparent, "float");
        if (floatEl != null) {
            double[] f = parseDoubles(text(floatEl));
            if (f.length >= 1) {
                return f[0];
            }
        }
        return 1.0;
    }

    private static Map<String, ColladaMaterial> parseMaterials(Element root) {
        Map<String, ColladaMaterial> out = new LinkedHashMap<>();
        for (Element lib : all(root, "library_materials")) {
            for (Element material : children(lib, "material")) {
                String effectUrl = null;
                Element inst = first(material, "instance_effect");
                if (inst != null) {
                    effectUrl = stripHash(attr(inst, "url"));
                }
                out.put(attr(material, "id"),
                        new ColladaMaterial(attr(material, "id"), attr(material, "name"), effectUrl));
            }
        }
        return out;
    }

    private static Map<String, ColladaImage> parseImages(Element root) {
        Map<String, ColladaImage> out = new LinkedHashMap<>();
        for (Element lib : all(root, "library_images")) {
            for (Element image : children(lib, "image")) {
                Element init = first(image, "init_from");
                out.put(attr(image, "id"),
                        new ColladaImage(attr(image, "id"), init == null ? null : text(init)));
            }
        }
        return out;
    }

    // ---- element helpers ------------------------------------------------------

    private static ColladaTransform.Kind transformKind(String localName) {
        switch (localName) {
            case "matrix":
                return ColladaTransform.Kind.MATRIX;
            case "translate":
                return ColladaTransform.Kind.TRANSLATE;
            case "rotate":
                return ColladaTransform.Kind.ROTATE;
            case "scale":
                return ColladaTransform.Kind.SCALE;
            case "uniform_scale":
                return ColladaTransform.Kind.UNIFORM_SCALE;
            case "lookat":
                return ColladaTransform.Kind.LOOKAT;
            default:
                return null;
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

    private static List<Element> all(Element parent, String localName) {
        return children(parent, localName);
    }

    private static List<Element> descendants(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Element e : elements(parent)) {
            if (localName.equals(local(e))) {
                out.add(e);
            }
            out.addAll(descendants(e, localName));
        }
        return out;
    }

    private static String local(Element e) {
        String localName = e.getLocalName();
        if (localName != null) {
            return localName;
        }
        String tag = e.getTagName();
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private static int attr(Element e, String name, int fallback) {
        String raw = attr(e, name);
        return raw == null ? fallback : (int) parseLong(raw, fallback);
    }

    private static double attr(Element e, String name, double fallback) {
        String raw = attr(e, name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String text(Element e) {
        return e == null ? "" : e.getTextContent().trim();
    }

    private static String textOr(Element e) {
        String t = text(e);
        return t.isEmpty() ? null : t;
    }

    private static String textOfFirst(Element parent, String localName) {
        Element child = first(parent, localName);
        return child == null ? "" : text(child);
    }

    private static double[] parseDoubles(String raw) {
        if (raw == null || raw.isBlank()) {
            return new double[0];
        }
        String[] tokens = raw.trim().split("\\s+");
        List<Double> out = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            try {
                out.add(Double.parseDouble(token));
            } catch (NumberFormatException ignored) {
                // a non-numeric token (e.g. a name_array of ids) contributes no float
            }
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static long[] parseLongs(String raw) {
        if (raw == null || raw.isBlank()) {
            return new long[0];
        }
        String[] tokens = raw.trim().split("\\s+");
        long[] out = new long[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            out[i] = parseLong(tokens[i], 0);
        }
        return out;
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String stripHash(String ref) {
        if (ref == null) {
            return null;
        }
        return ref.startsWith("#") ? ref.substring(1) : ref;
    }

    /** A well-formedness failure while parsing the COLLADA stream. */
    public static final class ColladaParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ColladaParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
