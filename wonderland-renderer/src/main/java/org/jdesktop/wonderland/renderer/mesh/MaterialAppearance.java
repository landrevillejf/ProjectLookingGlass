/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the resolved appearance a Collada material maps to.
 *
 * A pure value -- the diffuse colour (rgba) and, when the model's texture is
 * actually present in the store, the path to it. The Java 3D Material / Texture2D
 * objects are built later by the probe-only factory; computing *what* each
 * material becomes (colour, and textured vs. colour-fallback) is headless-testable
 * and lives here.
 */
package org.jdesktop.wonderland.renderer.mesh;

/**
 * The colour-and-texture binding for one material. Immutable.
 *
 * <p>By default a material is <em>unlit</em> ({@link #lit()} is false), which is
 * the faithful rendering of the shipped pre-lit art. {@code docs/wonderland-
 * roadmap.md} P0.1 explains why shading is opt-in; the lighting fields below are
 * inert unless a caller asks a material to be lit.
 */
public final class MaterialAppearance {

    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;
    private final String texturePath;

    // ---- opt-in lighting -------------------------------------------------
    //
    // Java 3D shades only an Appearance that carries a Material, and the shipped
    // Wonderland art is pre-lit -- its textures already hold the baked light --
    // so the faithful default is a flat, unlit colour (lit == false). A caller
    // that wants real shading opts in per material: lit() then drives
    // J3dMeshFactory to build a Material from the ambient/specular/emissive/
    // shininess below, and WorldScene to install a light rig. These fields are
    // read but never applied unless lit() is true, so leaving them at their
    // defaults changes no pixel of a pre-lit world.
    private final boolean lit;
    private final float shininess;
    private final float ambientRed;
    private final float ambientGreen;
    private final float ambientBlue;
    private final float specularRed;
    private final float specularGreen;
    private final float specularBlue;
    private final float emissiveRed;
    private final float emissiveGreen;
    private final float emissiveBlue;

    // ---- multi-layer materials (roadmap P2.1) ----------------------------
    //
    // A COLLADA effect's common profile can name more than a diffuse image: a
    // normal map and a specular map ride alongside it. Java 3D 1.7.2's fixed
    // pipeline has no slot for either -- a normal map only becomes pixels under a
    // GLSL program (roadmap P2.2) -- so these paths are CARRIED and RESOLVED here
    // (which layer is present, headless and testable) and APPLIED only on the
    // shader path. Recording them costs a pre-lit world no pixel and lets the
    // binding plan tell a normal sampler apart from a diffuse one, which it could
    // not before: the old first-present scan would happily bind a normal map as
    // the colour texture. Null means the world named no such layer.
    private final String normalMapPath;
    private final String specularMapPath;

    // ---- environment reflection / refraction (roadmap P2.5) --------------
    //
    // A glossy or liquid surface can name more than its own textures: an
    // environment cube map to reflect, an index of refraction to bend the world
    // seen through it, and how strongly that environment shows (reflectivity).
    // Java 3D 1.7.2's fixed pipeline can bind a cube texture but cannot compute
    // the per-fragment Schlick fresnel that weights reflection against a
    // Snell-refracted lookup -- that is a GLSL program's job (see the
    // EnvironmentPlan ceiling) -- so these fields are CARRIED and RESOLVED here
    // and APPLIED only on the shader path. Defaults (no cube map, reflectivity
    // 0, ior 1) cost a pre-P2.5 world no pixel, and resolving them is pure data.
    private final String environmentMapPath;
    private final float reflectivity;
    private final float refractionIor;

    /** An unlit (pre-lit-art) material: the faithful default, no shading. */
    public MaterialAppearance(float red, float green, float blue, float alpha,
                              String texturePath) {
        this(red, green, blue, alpha, texturePath, false,
                null, null, 0f, null, null, null, null, 0f, 1f);
    }

    /**
     * A material that may ask to be shaded. A null {@code ambient} defaults to
     * the diffuse colour (so a lit surface is never invisible in shadow), a null
     * {@code specular} to a neutral grey highlight, and a null {@code emissive}
     * to black. Each array is copied, so a caller cannot mutate this value after
     * construction.
     */
    public MaterialAppearance(float red, float green, float blue, float alpha,
                              String texturePath, boolean lit,
                              float[] ambient, float[] specular,
                              float shininess, float[] emissive) {
        this(red, green, blue, alpha, texturePath, lit,
                ambient, specular, shininess, emissive, null, null, null, 0f, 1f);
    }

    /**
     * The full material: the shading fields above plus the roadmap P2.1 extra
     * texture layers. {@code normalMapPath} and {@code specularMapPath} are the
     * resolved store paths of those images when the effect names one the model
     * actually ships, else null. They are inert until the shader path applies
     * them (a fixed-pipeline Java 3D cannot), but resolving them is pure data.
     */
    public MaterialAppearance(float red, float green, float blue, float alpha,
                              String texturePath, boolean lit,
                              float[] ambient, float[] specular,
                              float shininess, float[] emissive,
                              String normalMapPath, String specularMapPath) {
        this(red, green, blue, alpha, texturePath, lit,
                ambient, specular, shininess, emissive, normalMapPath, specularMapPath,
                null, 0f, 1f);
    }

    /**
     * The complete material: the P2.1 texture layers above plus the roadmap P2.5
     * environment. {@code environmentMapPath} is the resolved store path of the
     * cube map the effect names (null for none); {@code reflectivity} is clamped
     * to [0,1] and is how much of the environment shows over the diffuse; a
     * {@code refractionIor} of 1 (or a non-positive value, which reads as 1)
     * means the surface bends nothing. None of it becomes pixels on a fixed
     * pipeline -- recording it is pure data, applying it is the shader path.
     */
    public MaterialAppearance(float red, float green, float blue, float alpha,
                              String texturePath, boolean lit,
                              float[] ambient, float[] specular,
                              float shininess, float[] emissive,
                              String normalMapPath, String specularMapPath,
                              String environmentMapPath, float reflectivity,
                              float refractionIor) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        this.texturePath = texturePath;
        this.lit = lit;
        this.shininess = Math.max(0f, shininess);
        this.normalMapPath = blankToNull(normalMapPath);
        this.specularMapPath = blankToNull(specularMapPath);
        this.environmentMapPath = blankToNull(environmentMapPath);
        this.reflectivity = Math.max(0f, Math.min(1f, reflectivity));
        this.refractionIor = refractionIor > 0f ? refractionIor : 1f;
        float[] amb = orDefault(ambient, red, green, blue);
        this.ambientRed = amb[0];
        this.ambientGreen = amb[1];
        this.ambientBlue = amb[2];
        float[] spec = orDefault(specular, 0.2f, 0.2f, 0.2f);
        this.specularRed = spec[0];
        this.specularGreen = spec[1];
        this.specularBlue = spec[2];
        float[] emis = orDefault(emissive, 0f, 0f, 0f);
        this.emissiveRed = emis[0];
        this.emissiveGreen = emis[1];
        this.emissiveBlue = emis[2];
    }

    /** A blank or whitespace path is no layer, so it stores as null. */
    private static String blankToNull(String path) {
        return path == null || path.isBlank() ? null : path;
    }

    /** The array's first three values, or the fallback when it is null/short. */
    private static float[] orDefault(float[] value, float fbR, float fbG, float fbB) {
        if (value != null && value.length >= 3) {
            return new float[] {value[0], value[1], value[2]};
        }
        return new float[] {fbR, fbG, fbB};
    }

    public float red() {
        return red;
    }

    public float green() {
        return green;
    }

    public float blue() {
        return blue;
    }

    public float alpha() {
        return alpha;
    }

    /** The present texture path, or null when the material renders untextured. */
    public String texturePath() {
        return texturePath;
    }

    public boolean isTextured() {
        return texturePath != null;
    }

    /** The diffuse rgba as a four-element float array. */
    public float[] rgba() {
        return new float[] {red, green, blue, alpha};
    }

    /** Whether this material asks to be shaded; false renders it flat, as before. */
    public boolean lit() {
        return lit;
    }

    /** The Java 3D Material shininess, clamped to a non-negative value. */
    public float shininess() {
        return shininess;
    }

    /** The ambient reflectance as an rgb triple; defaults to the diffuse colour. */
    public float[] ambient() {
        return new float[] {ambientRed, ambientGreen, ambientBlue};
    }

    /** The specular reflectance as an rgb triple. */
    public float[] specular() {
        return new float[] {specularRed, specularGreen, specularBlue};
    }

    /** The self-lit emissive term as an rgb triple; defaults to black. */
    public float[] emissive() {
        return new float[] {emissiveRed, emissiveGreen, emissiveBlue};
    }

    /** The resolved normal-map store path, or null when the effect names none. */
    public String normalMapPath() {
        return normalMapPath;
    }

    /** The resolved specular-map store path, or null when the effect names none. */
    public String specularMapPath() {
        return specularMapPath;
    }

    /** Whether the material carries a normal-map layer (inert until P2.2 applies it). */
    public boolean hasNormalMap() {
        return normalMapPath != null;
    }

    /** Whether the material carries a specular-map layer (inert until P2.2 applies it). */
    public boolean hasSpecularMap() {
        return specularMapPath != null;
    }

    /** The resolved environment cube-map path, or null when the effect names none. */
    public String environmentMapPath() {
        return environmentMapPath;
    }

    /** Whether the material carries an environment cube map (inert until P2.5 applies it). */
    public boolean hasEnvironmentMap() {
        return environmentMapPath != null;
    }

    /** How strongly the environment shows over the diffuse, clamped to [0,1]. */
    public float reflectivity() {
        return reflectivity;
    }

    /** The index of refraction; 1 means the surface bends nothing. */
    public float refractionIor() {
        return refractionIor;
    }

    /** Whether the material asks for refraction, i.e. a bending index (ior != 1). */
    public boolean hasRefraction() {
        return refractionIor != 1f;
    }

    /**
     * How many texture layers the effect resolved: 0 (solid colour), 1 (diffuse
     * only -- everything a fixed-pipeline Java 3D can draw) up to 3 with a normal
     * and specular map. A layer count above 1 is the headless, testable proof
     * that a multi-layer material was read correctly; the extra layers only
     * become pixels on the shader path (roadmap P2.2).
     */
    public int layerCount() {
        int layers = 0;
        if (texturePath != null) {
            layers++;
        }
        if (normalMapPath != null) {
            layers++;
        }
        if (specularMapPath != null) {
            layers++;
        }
        return layers;
    }

    @Override
    public String toString() {
        return "MaterialAppearance{rgba=(" + red + ", " + green + ", " + blue + ", "
                + alpha + "), texture=" + texturePath + ", lit=" + lit
                + ", normalMap=" + normalMapPath + ", specularMap=" + specularMapPath
                + ", environmentMap=" + environmentMapPath + ", reflectivity=" + reflectivity
                + ", ior=" + refractionIor + "}";
    }
}
