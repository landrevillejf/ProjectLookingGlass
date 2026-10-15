/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: resolve a material symbol to a renderable appearance.
 *
 * Follows exactly the chase the M5 texture inventory already walks --
 * material symbol -> instance_effect -> effect -> sampler -> image -> normalized
 * init_from path -- then decides whether the effect's texture is *actually present*
 * in the deployed model (the path the loader put in presentTextures) or missing. A
 * present sampler binds the texture and keeps the effect's diffuse colour as a tint;
 * a missing one (or a texture-only diffuse with no colour) degrades to a solid
 * colour, mirroring the 2006 loader treating a missing texture as a warning, never a
 * failure. All of this is pure data selection -- the Texture2D/Material build stays
 * in the probe-only factory.
 */
package org.jdesktop.wonderland.renderer.mesh;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaEffect;
import org.jdesktop.wonderland.modules.collada.ColladaImage;
import org.jdesktop.wonderland.modules.collada.ColladaMaterial;

/** Maps a COLLADA material id to the {@link MaterialAppearance} it renders as. */
public final class TextureBindingPlan {

    /** The neutral grey used when an effect carries no diffuse colour constant. */
    private static final float[] DEFAULT_COLOR = {0.5f, 0.5f, 0.5f, 1.0f};

    private final ColladaDocument document;
    private final Set<String> presentTextures;

    public TextureBindingPlan(ColladaDocument document, Collection<String> presentTextures) {
        this.document = document;
        // Insertion-ordered: the inventory is the loader's encounter order, and the
        // match below scans it, so an unordered set would make the texture chosen
        // for an ambiguous name vary between runs of the same world.
        this.presentTextures = presentTextures == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(presentTextures);
    }

    /**
     * The appearance for one material. A material that resolves to nothing
     * (missing material/effect) still yields a defensible opaque grey so the shape
     * renders rather than dropping out.
     *
     * @param materialId a {@code <library_materials>} id; the caller has already
     *        turned the primitive's binding symbol into its target, because that
     *        binding is scoped to the geometry instance, not to the document
     */
    public MaterialAppearance forMaterial(String materialId) {
        ColladaEffect effect = resolveEffect(materialId);
        if (effect == null) {
            return defaultAppearance();
        }
        float[] rgba = diffuseRgba(effect);
        // Roadmap P2.1: one walk over the samplers, but each one is CLASSIFIED by
        // its own name before its image is consulted. A sampler named *normal/
        // bump/offset becomes the normal layer, *spec the specular layer, and only
        // the rest compete to be the diffuse colour. Before this, the plain
        // first-present scan would happily bind a normal map as the colour
        // texture -- pixels of a tangent-space map modulating a surface looks
        // like nothing the author ever intended.
        String diffuse = null;
        String normal = null;
        String spec = null;
        for (Map.Entry<String, String> sampler : effect.samplers().entrySet()) {
            String imageId = sampler.getValue();
            if (imageId == null) {
                continue;
            }
            ColladaImage image = document.image(imageId);
            String served = servedPath(image == null ? null : image.normalizedPath());
            if (served == null) {
                continue;
            }
            if (isNormalLayer(sampler.getKey())) {
                if (normal == null) {
                    normal = served;
                }
            } else if (isSpecularLayer(sampler.getKey())) {
                if (spec == null) {
                    spec = served;
                }
            } else if (diffuse == null) {
                diffuse = served;
            }
        }
        if (diffuse == null && normal != null) {
            // No ordinary sampler was present: an exporter that shipped ONE
            // texture and named it "normal" (or the common COLLADA id pattern
            // "sampler-imageN-normal") should not leave the shape untextured
            // where the old first-present scan rendered it. Bind it as diffuse
            // AND record it as the normal layer, so the shader path knows both
            // jobs while the fixed pipeline keeps drawing what it drew before.
            diffuse = normal;
        }
        if (diffuse == null && spec != null) {
            diffuse = spec; // same reasoning, one layer to the other name
        }
        if (diffuse == null && normal == null && spec == null) {
            return new MaterialAppearance(rgba[0], rgba[1], rgba[2], rgba[3], null);
        }
        return new MaterialAppearance(rgba[0], rgba[1], rgba[2], rgba[3], diffuse,
                false, null, null, 0f, null, normal, spec);
    }

    /** A sampler whose name says it feeds tangent-space normals, not colour. */
    private static boolean isNormalLayer(String samplerName) {
        String name = samplerName == null ? "" : samplerName.toLowerCase();
        return name.contains("normal") || name.contains("bump") || name.contains("offset");
    }

    /** A sampler whose name says it feeds the specular/gloss layer, not colour. */
    private static boolean isSpecularLayer(String samplerName) {
        String name = samplerName == null ? "" : samplerName.toLowerCase();
        return name.contains("spec");
    }

    /**
     * The appearances for every distinct material id the primitives reference, in
     * encounter order, so the factory can look one up per shape.
     */
    public Map<String, MaterialAppearance> forAll(List<String> materialIds) {
        Map<String, MaterialAppearance> out = new LinkedHashMap<>();
        for (String id : materialIds) {
            out.putIfAbsent(id, forMaterial(id));
        }
        return out;
    }

    private ColladaEffect resolveEffect(String materialId) {
        if (document == null || materialId == null) {
            return null;
        }
        ColladaMaterial material = document.material(materialId);
        if (material == null || material.instanceEffectUrl() == null) {
            return null;
        }
        return document.effect(material.instanceEffectUrl());
    }

    /** The diffuse rgba, or the default grey when the diffuse is a texture not a constant. */
    private static float[] diffuseRgba(ColladaEffect effect) {
        double[] diffuse = effect.diffuseColor();
        // A null / short diffuse is the texture-only <diffuse><texture/></diffuse>
        // case -- no constant colour to read; fall back to the neutral grey but
        // still honour the effect's transparency factor so a see-through material
        // keeps its alpha even without a colour constant.
        if (diffuse == null || diffuse.length < 3) {
            return new float[] {DEFAULT_COLOR[0], DEFAULT_COLOR[1], DEFAULT_COLOR[2],
                    (float) effect.transparency()};
        }
        float r = (float) diffuse[0];
        float g = (float) diffuse[1];
        float b = (float) diffuse[2];
        // COLLADA transparency: the loader records 1.0 as fully opaque; a 4-wide
        // diffuse also carries its own alpha, which wins when present.
        float a = diffuse.length >= 4 ? (float) diffuse[3] : (float) effect.transparency();
        return new float[] {r, g, b, a};
    }

    /**
     * The first sampler whose image resolves to a path the store actually holds.
     *
     * <p>Roadmap P2.1 retired the use of this scan inside {@link #forMaterial}
     * -- classification came first -- but the helper stays: it is the honest
     * answer to "does this effect ship any present texture at all", and a
     * caller that does not care which layer it is can ask without re-walking
     * the chase.</p>
     */
    private String firstPresentTexture(ColladaEffect effect) {
        if (document == null) {
            return null;
        }
        for (String imageId : effect.samplers().values()) {
            ColladaImage image = document.image(imageId);
            String served = servedPath(image == null ? null : image.normalizedPath());
            if (served != null) {
                return served;
            }
        }
        return null;
    }

    /**
     * The inventory entry that names this image, or null when the store has it not.
     *
     * <p>The two sides speak different dialects, and demanding an equal string was
     * the bug that made every world grey: {@code <init_from>} is written relative
     * to the .dae ("untitled/texture0.png"), while the loader hands back the path
     * it actually fetches, resolved against the model's own directory inside the
     * module ("JapanesePavilion7.kmz/models/untitled/texture0.png"). The image is
     * present exactly when the inventory entry ends on it, at a directory
     * boundary; the shortest such entry wins, since that is the one nearest the
     * model. The inventory's own spelling is returned, because that is the key the
     * asset store answers.</p>
     */
    private String servedPath(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) {
            return null;
        }
        if (presentTextures.contains(imagePath)) {
            return imagePath;
        }
        String key = imagePath.startsWith("./") ? imagePath.substring(2) : imagePath;
        String best = null;
        for (String candidate : presentTextures) {
            if (candidate.equals(key) || candidate.endsWith("/" + key)) {
                if (best == null || candidate.length() < best.length()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static MaterialAppearance defaultAppearance() {
        return new MaterialAppearance(DEFAULT_COLOR[0], DEFAULT_COLOR[1],
                DEFAULT_COLOR[2], DEFAULT_COLOR[3], null);
    }
}
