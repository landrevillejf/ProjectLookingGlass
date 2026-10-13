/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the render-ready mesh data a Collada primitive
 * converts to, before any Java 3D object is built.
 *
 * This is the data-only half of the deferred M6 renderer: a flat, unshared
 * triangle-soup the Collada draw element expands to -- per-vertex positions and
 * (when the authoring tool emitted them) normals and texture coordinates, plus
 * the triangle index list -- keyed to the primitive's material symbol. Keeping
 * the expansion in a pure value type means the geometry walk (the part that used
 * to live inside the 2006 jME/jogamp loader) is exercised headless; only the
 * Java 3D GeometryArray build that consumes it needs a live canvas.
 */
package org.jdesktop.wonderland.renderer.mesh;

import java.util.Arrays;
import java.util.Objects;

/** A primitive's flat vertex/index data, ready for a Java 3D array. Immutable. */
public final class ColladaMeshData {

    private final float[] coordinates;
    private final float[] normals;
    private final float[] texCoords;
    private final int[] triangleIndices;
    private final String material;

    /**
     * @param coordinates   xyz per vertex, length {@code 3 * vertexCount}
     * @param normals       xyz per vertex, or an empty array when the source had none
     * @param texCoords     uv per vertex, or an empty array when the source had none
     * @param triangleIndices every three entries name one triangle into the arrays above
     * @param material      the primitive's material symbol (may be null)
     */
    public ColladaMeshData(float[] coordinates, float[] normals, float[] texCoords,
                           int[] triangleIndices, String material) {
        this.coordinates = Objects.requireNonNull(coordinates, "coordinates");
        this.normals = normals == null ? new float[0] : normals;
        this.texCoords = texCoords == null ? new float[0] : texCoords;
        this.triangleIndices = Objects.requireNonNull(triangleIndices, "triangleIndices");
        this.material = material;
    }

    public float[] coordinates() {
        return coordinates.clone();
    }

    public float[] normals() {
        return normals.clone();
    }

    public float[] texCoords() {
        return texCoords.clone();
    }

    public int[] triangleIndices() {
        return triangleIndices.clone();
    }

    public String material() {
        return material;
    }

    /** The number of vertices in the (unshared) arrays. */
    public int vertexCount() {
        return coordinates.length / 3;
    }

    /** The number of triangles the index list describes. */
    public int triangleCount() {
        return triangleIndices.length / 3;
    }

    public boolean hasNormals() {
        return normals.length == coordinates.length;
    }

    public boolean hasTexCoords() {
        return texCoords.length / 2 == vertexCount();
    }

    @Override
    public String toString() {
        return "ColladaMeshData{vertices=" + vertexCount()
                + ", triangles=" + triangleCount()
                + ", normals=" + hasNormals()
                + ", texCoords=" + hasTexCoords()
                + ", material=" + material
                + ", coords=" + Arrays.toString(coordinates) + "}";
    }
}
