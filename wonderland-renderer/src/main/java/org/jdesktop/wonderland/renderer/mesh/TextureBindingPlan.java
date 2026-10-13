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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        this.presentTextures = presentTextures == null
                ? Collections.emptySet()
                : new HashSet<>(presentTextures);
    }

    /**
     * The appearance for one material symbol. A material that resolves to nothing
     * (missing material/effect) still yields a defensible opaque grey so the shape
     * renders rather than dropping out.
     */
    public MaterialAppearance forMaterial(String materialId) {
        ColladaEffect effect = resolveEffect(materialId);
        if (effect == null) {
            return defaultAppearance();
        }
        float[] rgba = diffuseRgba(effect);
        String texture = firstPresentTexture(effect);
        if (texture != null) {
            return new MaterialAppearance(rgba[0], rgba[1], rgba[2], rgba[3], texture);
        }
        return new MaterialAppearance(rgba[0], rgba[1], rgba[2], rgba[3], null);
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

    /** The first sampler whose image resolves to a path the store actually holds. */
    private String firstPresentTexture(ColladaEffect effect) {
        if (document == null) {
            return null;
        }
        for (String imageId : effect.samplers().values()) {
            ColladaImage image = document.image(imageId);
            if (image == null) {
                continue;
            }
            String path = image.normalizedPath();
            if (path != null && presentTextures.contains(path)) {
                return path;
            }
        }
        return null;
    }

    private static MaterialAppearance defaultAppearance() {
        return new MaterialAppearance(DEFAULT_COLOR[0], DEFAULT_COLOR[1],
                DEFAULT_COLOR[2], DEFAULT_COLOR[3], null);
    }
}
