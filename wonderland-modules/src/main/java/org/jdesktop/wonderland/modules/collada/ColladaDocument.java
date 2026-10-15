/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): the COLLADA document aggregate.
 *
 * The parsed libraries keyed by id, the root scene selection, and the reference
 * walks the asset pipeline needs -- world matrices over the scene, and the set of
 * texture file paths the model's materials actually sample (material -> effect ->
 * sampler -> image -> normalised init_from). Those paths are what M5b cross-checks
 * against the deployed model's loader-data texture list. Building the actual Java
 * 3D shapes from this data stays deferred to M6.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A whole parsed COLLADA document. */
public final class ColladaDocument {

    private final ColladaAsset asset;
    private final Map<String, ColladaGeometry> geometries;
    private final Map<String, ColladaMaterial> materials;
    private final Map<String, ColladaEffect> effects;
    private final Map<String, ColladaImage> images;
    private final Map<String, ColladaVisualScene> visualScenes;
    private final String rootSceneId;
    private final Map<String, ColladaAnimation> animations;

    public ColladaDocument(ColladaAsset asset,
                           Map<String, ColladaGeometry> geometries,
                           Map<String, ColladaMaterial> materials,
                           Map<String, ColladaEffect> effects,
                           Map<String, ColladaImage> images,
                           Map<String, ColladaVisualScene> visualScenes,
                           String rootSceneId) {
        this(asset, geometries, materials, effects, images, visualScenes, rootSceneId, Map.of());
    }

    /** M7c: the full form carrying the parsed {@code <library_animations>} records. */
    public ColladaDocument(ColladaAsset asset,
                           Map<String, ColladaGeometry> geometries,
                           Map<String, ColladaMaterial> materials,
                           Map<String, ColladaEffect> effects,
                           Map<String, ColladaImage> images,
                           Map<String, ColladaVisualScene> visualScenes,
                           String rootSceneId,
                           Map<String, ColladaAnimation> animations) {
        this.asset = asset == null ? new ColladaAsset(null, 1.0, null, null, null) : asset;
        // Every library below is exposed as a keyed VIEW whose iteration order is
        // part of its contract: animations() is documented parse order (the
        // renderer's clip catalog is built from it), rootVisualScene() falls back
        // to the "first" scene, and texturePaths() promises encounter order over
        // effects.values(). Map.copyOf hands back an unordered immutable map whose
        // iteration order is unspecified and varies between JVM runs, so the
        // contracts would silently break (and the tests that pin them would turn
        // flaky). Wrap the caller's LinkedHashMap instead: same immutability, and
        // the document order survives.
        this.geometries = preserveOrder(geometries);
        this.materials = preserveOrder(materials);
        this.effects = preserveOrder(effects);
        this.images = preserveOrder(images);
        this.visualScenes = preserveOrder(visualScenes);
        this.rootSceneId = rootSceneId;
        this.animations = preserveOrder(animations);
    }

    /** An unmodifiable copy that keeps the source map's iteration order. */
    private static <V> Map<String, V> preserveOrder(Map<String, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public ColladaAsset asset() {
        return asset;
    }

    public Set<String> geometryIds() {
        return geometries.keySet();
    }

    /**
     * The ids in each library, in parse order.
     *
     * <p>These are the read-only inventory a compound reader needs to fold
     * several documents into one: an MTGame {@code .mtg} scene names a dozen
     * COLLADA files whose ids (every SketchUp/Google-Earth export reuses
     * {@code geometries_0}, {@code material0}...) collide across documents, so
     * merging means walking every library of each source and re-keying it under
     * a namespace. Exposing the key sets -- not the mutable maps -- keeps the
     * aggregate's iteration-order contract (see the constructor) intact.
     */
    public Set<String> materialIds() {
        return materials.keySet();
    }

    public Set<String> effectIds() {
        return effects.keySet();
    }

    public Set<String> imageIds() {
        return images.keySet();
    }

    public Set<String> visualSceneIds() {
        return visualScenes.keySet();
    }

    public ColladaGeometry geometry(String id) {
        return geometries.get(id);
    }

    public ColladaMaterial material(String id) {
        return materials.get(id);
    }

    public ColladaEffect effect(String id) {
        return effects.get(id);
    }

    public ColladaImage image(String id) {
        return images.get(id);
    }

    public ColladaVisualScene visualScene(String id) {
        return visualScenes.get(id);
    }

    /** The scene the document's {@code <scene>} selects, falling back to the only/first scene. */
    public ColladaVisualScene rootVisualScene() {
        if (rootSceneId != null && visualScenes.containsKey(rootSceneId)) {
            return visualScenes.get(rootSceneId);
        }
        return visualScenes.values().stream().findFirst().orElse(null);
    }

    /** Every node in the root scene with its computed world matrix, flattened. */
    public List<ColladaNode.WorldMatrix> worldMatrices() {
        List<ColladaNode.WorldMatrix> out = new ArrayList<>();
        ColladaVisualScene root = rootVisualScene();
        if (root != null) {
            for (ColladaNode node : root.nodes()) {
                node.collectWorldMatrices(null, out);
            }
        }
        return out;
    }

    /**
     * The distinct texture file paths this model's materials sample, in encounter
     * order. Chase: effect samplers -> images -> {@link ColladaImage#normalizedPath()}.
     * Materials/effects that resolve to nothing are skipped (a partial authoring
     * export is not a load failure at the inventory stage).
     */
    public Set<String> texturePaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (ColladaEffect effect : effects.values()) {
            for (String imageId : effect.samplers().values()) {
                ColladaImage image = images.get(imageId);
                if (image != null && image.normalizedPath() != null) {
                    paths.add(image.normalizedPath());
                }
            }
        }
        return paths;
    }

    /**
     * The {@code <library_animations>} records keyed by id, in parse order.
     * M5 documents never had animations; an empty map is the honest answer for
     * every shipped static model and for the two un-rigged placeholder bodies.
     */
    public Map<String, ColladaAnimation> animations() {
        return animations;
    }

    public int geometryCount() {
        return geometries.size();
    }

    /** Number of animation records (M7c). */
    public int animationCount() {
        return animations.size();
    }

    public int materialCount() {
        return materials.size();
    }

    public int imageCount() {
        return images.size();
    }
}
