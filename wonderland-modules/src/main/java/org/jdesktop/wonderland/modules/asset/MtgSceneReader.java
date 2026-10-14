/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (assetmgr): the MTGame scene (.mtg) as one COLLADA document.
 *
 * The meeting complex is not a COLLADA model and never was. Its deployed model is
 * an {@code OrientationWorld.mtg} -- an MTGame scene file, a plain-text list of
 * {@code Instance <name> org.jdesktop.mtgame.ColladaInstance tx ty tz qx qy qz qw
 * sx sy sz <file.dae.gz>} records that each drop one COLLADA artifact into the
 * scene under a transform, plus a handful of {@code Geometry}/{@code Shader}
 * records for the engine's own ground shaders and a {@code JMEWaterInstance}. In
 * 2006 the {@code MtgLoader} handed the whole file to the {@code mtgame} engine,
 * which built a scene graph of many models; this port has no mtgame, so a
 * {@code .mtg} was the one shipped art format with no reader -- and because the
 * meeting complex's ENTIRE ground and building live in that one file, the world
 * loaded with its whole world missing. The combined world embeds the same file,
 * so it lost the same thing.
 *
 * This reader is that missing interpreter, and it produces one COLLADA document
 * rather than teaching every downstream consumer (placement, the renderer seam,
 * the world browser, the probe) about a second format. Each {@code ColladaInstance}
 * is parsed from its own {@code .dae(.gz)} and folded into a single aggregate:
 * the libraries are re-keyed under a per-source namespace (every Google-Earth /
 * SketchUp export reuses {@code geometries_0}, {@code material0}..., so twelve
 * files' ids collide unless each document's are prefixed), and one node per
 * instance carries its translate·rotate·scale so the models land where the author
 * placed them. The scene keeps the {@code .dep}'s own background transform for the
 * one thing the engine applied to the WHOLE scene -- the Z-up to Y-up flip --
 * because the aggregate is one model result on one cell, exactly as a COLLADA
 * model is; that is why folding to a document, not to a dozen synthetic cells, is
 * the faithful shape here.
 *
 * A record this reader cannot honour -- a water instance, a missing .dae, a
 * malformed line -- is skipped, not fatal: a complex that renders its terrain and
 * building without its pond or a stray shelter is the honest partial load the
 * 2006 client produced for a module it could not fully open.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.jdesktop.wonderland.modules.collada.ColladaAsset;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaEffect;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.jdesktop.wonderland.modules.collada.ColladaImage;
import org.jdesktop.wonderland.modules.collada.ColladaMaterial;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jdesktop.wonderland.modules.collada.ColladaParser;
import org.jdesktop.wonderland.modules.collada.ColladaTransform;
import org.jdesktop.wonderland.modules.collada.ColladaVisualScene;

/** Reads one MTGame {@code .mtg} scene into a single compound {@link ColladaDocument}. */
public final class MtgSceneReader {

    /** The MTGame class name a COLLADA-bearing instance carries. */
    private static final String COLLADA_INSTANCE = "ColladaInstance";

    private final ColladaParser colladaParser = new ColladaParser();

    /**
     * Fold an already-read {@code .mtg} into one document.
     *
     * @param store    the composite store that also resolves the instance files
     * @param module   the module the {@code .mtg} resolved to (its siblings live there)
     * @param mtgPath  the in-module path of the {@code .mtg}, its instance base dir
     * @param mtgText  the scene file's text
     * @return the aggregate, or null when the file names no readable instance
     */
    public ColladaDocument read(WorldStore store, String module, String mtgPath,
            String mtgText) {
        if (mtgText == null || mtgText.isEmpty()) {
            return null;
        }
        String baseDir = WlaRef.parentDir(mtgPath);
        Map<String, ColladaGeometry> geometries = new LinkedHashMap<>();
        Map<String, ColladaMaterial> materials = new LinkedHashMap<>();
        Map<String, ColladaEffect> effects = new LinkedHashMap<>();
        Map<String, ColladaImage> images = new LinkedHashMap<>();
        Map<String, ColladaVisualScene> scenes = new LinkedHashMap<>();
        List<ColladaNode> rootNodes = new ArrayList<>();

        // Parse each distinct source .dae once; its namespaced subtree and merged
        // libraries are shared by every instance of the same file (the complex
        // repeats shelters and trees), so a file folded twice neither double-counts
        // its geometry nor wastes a second parse.
        Map<String, List<ColladaNode>> subtreeByFile = new LinkedHashMap<>();
        int sequence = 0;
        for (Instance instance : parseInstances(mtgText)) {
            if (!instance.collada()) {
                continue; // a water or engine-generated record has no .dae to fold
            }
            List<ColladaNode> subtree = subtreeByFile.get(instance.file());
            if (subtree == null) {
                subtree = loadSubtree(store, module, baseDir, instance.file(),
                        geometries, materials, effects, images);
                subtreeByFile.put(instance.file(), subtree);
            }
            if (subtree.isEmpty()) {
                continue; // a missing/corrupt .dae costs this instance, not the scene
            }
            ColladaNode placed = new ColladaNode("mtg-instance-" + sequence++,
                    instance.name(), "NODE", transformOf(instance), subtree,
                    List.of(), List.of());
            rootNodes.add(placed);
        }

        if (rootNodes.isEmpty()) {
            return null;
        }
        scenes.put("mtg-root", new ColladaVisualScene("mtg-root", "mtg-root", rootNodes));
        // The .dae authors' up_axis / unit are irrelevant to this pipeline (nothing
        // reads them -- the cell's .dep applies the Z-up flip to the whole scene),
        // so the aggregate carries the neutral identity asset.
        return new ColladaDocument(new ColladaAsset(null, 1.0, null, null, null),
                geometries, materials, effects, images, scenes, "mtg-root");
    }

    /**
     * Parse and namespace one source {@code .dae(.gz)} into the aggregate's library
     * maps, returning its root-scene nodes with every reference rewritten under a
     * per-file prefix. An empty list (never null) is the "this file is unusable"
     * answer the caller caches so a bad file is not re-read per instance.
     */
    private List<ColladaNode> loadSubtree(WorldStore store, String module, String baseDir,
            String file, Map<String, ColladaGeometry> geometries,
            Map<String, ColladaMaterial> materials, Map<String, ColladaEffect> effects,
            Map<String, ColladaImage> images) {
        String prefix = namespace(file);
        byte[] bytes = readBytes(store, module, WlaRef.resolve(baseDir, file));
        if (bytes == null) {
            return List.of();
        }
        ColladaDocument source;
        try {
            source = colladaParser.parse(inflate(bytes));
        } catch (IOException | RuntimeException ex) {
            return List.of(); // a COLLADA file this parser rejects drops this instance only
        }
        ColladaVisualScene root = source.rootVisualScene();
        if (root == null) {
            return List.of();
        }
        mergeLibraries(source, prefix, geometries, materials, effects, images);
        List<ColladaNode> out = new ArrayList<>();
        for (ColladaNode node : root.nodes()) {
            out.add(prefixNode(node, prefix));
        }
        return out;
    }

    /** Copy each source library into the aggregate under {@code prefix + id}. */
    private static void mergeLibraries(ColladaDocument source, String prefix,
            Map<String, ColladaGeometry> geometries, Map<String, ColladaMaterial> materials,
            Map<String, ColladaEffect> effects, Map<String, ColladaImage> images) {
        for (String id : source.geometryIds()) {
            geometries.put(prefix + id, source.geometry(id)); // mesh-internal source ids are per-geometry
        }
        for (String id : source.materialIds()) {
            ColladaMaterial m = source.material(id);
            materials.put(prefix + id, new ColladaMaterial(prefix + id, m.name(),
                    prefixRef(m.instanceEffectUrl(), prefix)));
        }
        for (String id : source.effectIds()) {
            ColladaEffect e = source.effect(id);
            Map<String, String> samplers = new LinkedHashMap<>();
            for (Map.Entry<String, String> s : e.samplers().entrySet()) {
                samplers.put(s.getKey(), prefixRef(s.getValue(), prefix));
            }
            effects.put(prefix + id, new ColladaEffect(prefix + id, e.name(),
                    e.technique(), e.diffuseColor(), e.transparency(), samplers));
        }
        for (String id : source.imageIds()) {
            ColladaImage img = source.image(id);
            images.put(prefix + id, new ColladaImage(prefix + id, img.initFrom()));
        }
    }

    /**
     * Rebuild one node with its id prefixed and its geometry / material / node
     * references pointing at the same namespaced keys the libraries now carry.
     */
    private static ColladaNode prefixNode(ColladaNode node, String prefix) {
        List<ColladaNode> children = new ArrayList<>();
        for (ColladaNode child : node.children()) {
            children.add(prefixNode(child, prefix));
        }
        List<ColladaNode.InstanceGeometry> geometries = new ArrayList<>();
        for (ColladaNode.InstanceGeometry ig : node.geometries()) {
            List<ColladaNode.InstanceMaterial> mats = new ArrayList<>();
            for (ColladaNode.InstanceMaterial im : ig.materials()) {
                mats.add(new ColladaNode.InstanceMaterial(im.symbol(),
                        prefixRef(im.target(), prefix), im.texcoords()));
            }
            geometries.add(new ColladaNode.InstanceGeometry(
                    "#" + prefixRef(ig.url(), prefix), mats));
        }
        List<String> nodeInstances = new ArrayList<>();
        for (String url : node.nodeInstanceUrls()) {
            nodeInstances.add("#" + prefixRef(url, prefix));
        }
        return new ColladaNode(prefix + node.id(), node.name(), node.type(),
                node.transforms(), children, geometries, nodeInstances);
    }

    /** {@code #id} or a bare id, re-keyed under {@code prefix} (the hash is the caller's). */
    private static String prefixRef(String ref, String prefix) {
        if (ref == null) {
            return null;
        }
        int hash = ref.lastIndexOf('#');
        String id = hash < 0 ? ref : ref.substring(hash + 1);
        return prefix + id;
    }

    /**
     * The instance's local transform as the COLLADA {@code <translate>},
     * {@code <rotate>} and {@code <scale>} chain that composes to T·R·S.
     *
     * <p>An MTGame {@code Instance} records its orientation as four numbers
     * {@code qx qy qz qw} that are not a Hamilton quaternion but an
     * {@code (axis, angle)} pair -- the same shape the {@code .dep}'s
     * {@code modelBGRotation} and jME's {@code fromAngleAxis} use, and the angle
     * is in radians. In the shipped meeting complex every instance's axis is the
     * unit {@code Y} and only the angle varies: it is 0 for the ground, the
     * building, the shelters, the trees and one shrub row -- so those must land
     * upright and unturned -- and a per-object yaw for the props (rocks, plants,
     * lamps). Reading these four as a raw quaternion, as an earlier cut of this
     * reader did, turns the axis-first {@code (0, 1, 0, 0)} into a spurious 180
     * degree flip and mirrors the whole complex; the angle is therefore fed to a
     * {@code <rotate>} (which takes degrees) after the radian-to-degree
     * conversion, and a zero angle is the identity it always was. The one thing
     * no headless read can prove -- whether the author's angle was radians or
     * degrees -- is visible only against the 2006 desktop, so the props are
     * flagged for a live-probe check rather than claimed here.</p>
     */
    private static List<ColladaTransform> transformOf(Instance i) {
        List<ColladaTransform> transforms = new ArrayList<>();
        transforms.add(new ColladaTransform(ColladaTransform.Kind.TRANSLATE, null,
                new double[]{i.tx(), i.ty(), i.tz()}));
        if (i.hasRotation()) {
            double axisLen = Math.sqrt(i.qx() * i.qx() + i.qy() * i.qy() + i.qz() * i.qz());
            if (axisLen > 1e-12) {
                transforms.add(new ColladaTransform(ColladaTransform.Kind.ROTATE, null,
                        new double[]{i.qx() / axisLen, i.qy() / axisLen, i.qz() / axisLen,
                                Math.toDegrees(i.qw())}));
            }
        }
        if (i.sx() != 1.0 || i.sy() != 1.0 || i.sz() != 1.0) {
            transforms.add(new ColladaTransform(ColladaTransform.Kind.SCALE, null,
                    new double[]{i.sx(), i.sy(), i.sz()}));
        }
        return transforms;
    }

    /** The scene's {@code Instance} records; every other line is context this reader ignores. */
    private static List<Instance> parseInstances(String text) {
        List<Instance> out = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("Instance ") || line.startsWith("Instance\t")) {
                Instance i = Instance.parse(line);
                if (i != null) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    /** A store asset's bytes, or null when absent. */
    private static byte[] readBytes(WorldStore store, String module, String path) {
        Optional<InputStream> in = store.open(module, path);
        if (in.isEmpty()) {
            return null;
        }
        try (InputStream stream = in.get()) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            return null;
        }
    }

    /** Inflate a gzip stream by its magic bytes; pass plain bytes through (a {@code .dae} may be raw). */
    private static InputStream inflate(byte[] bytes) throws IOException {
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(raw);
        }
        return raw;
    }

    /** A namespace-safe prefix for one source file, so its library ids cannot collide. */
    private static String namespace(String file) {
        String base = WlaRef.baseName(file)
                .replaceAll("(?i)\\.dae(\\.gz)?$", "")
                .replaceAll("[^A-Za-z0-9_]", "_");
        return base + "__";
    }

    /**
     * One {@code Instance} record: the name, whether it is a COLLADA fold, its
     * transform and the {@code .dae} file it drops into the scene.
     */
    private record Instance(String name, boolean collada, double tx, double ty, double tz,
            double qx, double qy, double qz, double qw, double sx, double sy, double sz,
            String file) {

        /**
         * Parse {@code Instance <name> <class> tx ty tz qx qy qz qw sx sy sz <file>}.
         * The eleven leading tokens are the keyword, the name, the MTGame class and
         * the ten transform numbers; the {@code .dae} file is the twelfth (index 13),
         * and a path with a space runs to the end of the line. Returns null (never
         * throws) for a record whose shape this reader does not handle -- a
         * non-numeric transform, a truncated line -- so a malformed author line
         * costs one object only.
         */
        static Instance parse(String line) {
            String[] t = line.split("\\s+");
            if (t.length < 14) {
                return null; // Instance, name, class, then 10 numbers, then a file
            }
            boolean collada = t[2].contains(COLLADA_INSTANCE);
            try {
                double[] v = new double[10];
                for (int i = 0; i < 10; i++) {
                    v[i] = Double.parseDouble(t[3 + i]);
                }
                StringBuilder file = new StringBuilder(t[13]);
                for (int i = 14; i < t.length; i++) {
                    file.append(' ').append(t[i]); // a path with a space is rare but legal
                }
                return new Instance(t[1], collada, v[0], v[1], v[2], v[3], v[4], v[5],
                        v[6], v[7], v[8], v[9], file.toString());
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        /**
         * Whether this instance carries a non-identity spin. The angle lives in the
         * fourth orientation number ({@code qw}); every shipped instance points its
         * axis at {@code Y} and differs only here, so a zero angle is no rotation at
         * all and the transform chain skips the {@code <rotate>} for it.
         */
        boolean hasRotation() {
            return qw != 0.0;
        }
    }

}
