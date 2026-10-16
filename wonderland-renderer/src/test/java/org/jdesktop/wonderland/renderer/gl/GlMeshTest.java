/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: GlMesh's shape (counts, attribute presence, the
 * material symbol, uploaded-flag) is pure and asserted headless; only the
 * VBO/VAO/EBO upload and draw need a live GL context (probe-verified).
 */
package org.jdesktop.wonderland.renderer.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.junit.jupiter.api.Test;

class GlMeshTest {

    private static ColladaMeshData triangle(boolean normals, boolean uv) {
        return new ColladaMeshData(
                new float[] { 0, 0, 0, 1, 0, 0, 0, 1, 0 },
                normals ? new float[] { 0, 0, 1, 0, 0, 1, 0, 0, 1 } : new float[0],
                uv ? new float[] { 0, 0, 1, 0, 0, 1 } : new float[0],
                new int[] { 0, 1, 2 },
                "cladding");
    }

    @Test
    void exposesTheSoupShape() {
        GlMesh mesh = new GlMesh(triangle(true, true));
        assertThat(mesh.vertexCount()).isEqualTo(3);
        assertThat(mesh.triangleCount()).isEqualTo(1);
        assertThat(mesh.hasNormals()).isTrue();
        assertThat(mesh.hasTexCoords()).isTrue();
        assertThat(mesh.material()).isEqualTo("cladding");
        assertThat(mesh.isUploaded()).isFalse();
    }

    @Test
    void missingAttributesAreReported() {
        GlMesh bare = new GlMesh(triangle(false, false));
        assertThat(bare.hasNormals()).isFalse();
        assertThat(bare.hasTexCoords()).isFalse();
    }

    @Test
    void nullDataIsRejected() {
        assertThatThrownBy(() -> new GlMesh(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drawAndDisposeBeforeUploadAreNoOps() {
        // A mesh that never reached the GPU must swallow draw/dispose without
        // touching a GL context (null here) -- a partially-built scene degrades
        // to "fewer objects", never to a NullPointerException.
        GlMesh mesh = new GlMesh(triangle(true, true));
        mesh.draw(null);
        mesh.dispose(null);
        assertThat(mesh.isUploaded()).isFalse();
    }
}
