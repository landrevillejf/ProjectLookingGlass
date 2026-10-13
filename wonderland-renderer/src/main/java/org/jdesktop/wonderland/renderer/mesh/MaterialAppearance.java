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

/** The colour-and-texture binding for one material. Immutable. */
public final class MaterialAppearance {

    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;
    private final String texturePath;

    public MaterialAppearance(float red, float green, float blue, float alpha,
                              String texturePath) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        this.texturePath = texturePath;
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

    @Override
    public String toString() {
        return "MaterialAppearance{rgba=(" + red + ", " + green + ", " + blue + ", "
                + alpha + "), texture=" + texturePath + "}";
    }
}
