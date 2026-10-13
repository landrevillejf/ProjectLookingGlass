/*
 * wonderland-modules M5a (colladaloader): a COLLADA <effect>.
 *
 * The FX profile_COMMON subset the loader reads: which shading technique, the
 * diffuse colour when one is a plain float4, the transparency factor, and the
 * sampler2D bindings that map a texture symbol to a <image> id. Everything here
 * is a data inventory -- the actual material/texture State objects are built by
 * the deferred M6 jME/jogamp renderer.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.Map;

/** A {@code <library_effects>/<effect>} element (profile_COMMON only). */
public final class ColladaEffect {

    private final String id;
    private final String name;
    private final String technique;
    private final double[] diffuseColor;
    private final double transparency;
    private final Map<String, String> samplers;

    /**
     * @param technique    the common technique tag (lambert / phong / blinn / constant)
     * @param diffuseColor the diffuse float4 as four doubles, or null when the diffuse
     *                     is a texture rather than a constant
     * @param transparency the {@code <transparent>} factor (1.0 = opaque when absent)
     * @param samplers     sampler id -> referenced image id (hash stripped)
     */
    public ColladaEffect(String id, String name, String technique,
                         double[] diffuseColor, double transparency,
                         Map<String, String> samplers) {
        this.id = id;
        this.name = name;
        this.technique = technique;
        this.diffuseColor = diffuseColor == null ? null : diffuseColor.clone();
        this.transparency = transparency;
        this.samplers = Map.copyOf(samplers);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String technique() {
        return technique;
    }

    public double[] diffuseColor() {
        return diffuseColor == null ? null : diffuseColor.clone();
    }

    public double transparency() {
        return transparency;
    }

    /** sampler id -> image id. */
    public Map<String, String> samplers() {
        return samplers;
    }
}
