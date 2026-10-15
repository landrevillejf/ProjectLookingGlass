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

    /** An unlit (pre-lit-art) material: the faithful default, no shading. */
    public MaterialAppearance(float red, float green, float blue, float alpha,
                              String texturePath) {
        this(red, green, blue, alpha, texturePath, false,
                null, null, 0f, null);
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
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        this.texturePath = texturePath;
        this.lit = lit;
        this.shininess = Math.max(0f, shininess);
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

    @Override
    public String toString() {
        return "MaterialAppearance{rgba=(" + red + ", " + green + ", " + blue + ", "
                + alpha + "), texture=" + texturePath + ", lit=" + lit + "}";
    }
}
