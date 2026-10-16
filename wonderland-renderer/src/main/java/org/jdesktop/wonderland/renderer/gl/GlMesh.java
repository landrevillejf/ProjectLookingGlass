/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: one draw-ready GPU mesh on JOGL.
 *
 * The pure half of the renderer (ColladaMeshBuilder) already expands a Collada
 * primitive into a flat triangle soup -- ColladaMeshData's positions / normals /
 * texture coordinates plus the triangle index list. This class is the JOGL half
 * that turns those arrays into GPU buffers: a VAO binding one VBO per attribute
 * (position=0, normal=1, texcoord=2, matching the layout qualifiers in
 * GlScene's program) and an element buffer for the indices. Upload and draw need
 * a live GL3, so they are probe-verified; the shape of the mesh (counts, whether
 * normals/texcoords exist, the material symbol) is pure and asserted headless.
 *
 * Buffers are created once (upload) and reused every frame (draw); dispose
 * releases them. A mesh that was never uploaded simply draws nothing, so a
 * partially-built scene degrades to "fewer objects", never to a GL error.
 */
package org.jdesktop.wonderland.renderer.gl;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2ES3;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;

/**
 * A ColladaMeshData uploaded to GPU buffers, drawable through a VAO.
 */
public final class GlMesh {

    /** Attribute locations, fixed to match GlScene's vertex-shader layout. */
    public static final int LOC_POSITION = 0;
    public static final int LOC_NORMAL = 1;
    public static final int LOC_TEXCOORD = 2;

    private final ColladaMeshData data;
    // [0]=VAO, [1]=position VBO, [2]=normal VBO, [3]=texcoord VBO, [4]=index EBO.
    private final int[] ids = new int[5];
    private boolean uploaded;

    /**
     * @param data the flat triangle soup to put on the GPU; not null
     */
    public GlMesh(ColladaMeshData data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        this.data = data;
    }

    public int vertexCount() {
        return data.vertexCount();
    }

    public int triangleCount() {
        return data.triangleCount();
    }

    public boolean hasNormals() {
        return data.hasNormals();
    }

    public boolean hasTexCoords() {
        return data.hasTexCoords();
    }

    public String material() {
        return data.material();
    }

    /** Whether {@link #upload} has run and {@link #draw} is a no-op-safe draw. */
    public boolean isUploaded() {
        return uploaded;
    }

    /**
     * Create the VAO/VBO/EBO and push the vertex and index data. Idempotent: a
     * second call is ignored (the buffers already hold this mesh).
     *
     * @param gl the current context's GL3-equivalent surface
     */
    public void upload(GL2ES3 gl) {
        if (uploaded) {
            return;
        }
        gl.glGenVertexArrays(1, ids, 0);
        gl.glGenBuffers(4, ids, 1);
        gl.glBindVertexArray(ids[0]);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, ids[1]);
        float[] pos = data.coordinates();
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) pos.length * Buffers.SIZEOF_FLOAT,
                Buffers.newDirectFloatBuffer(pos), GL.GL_STATIC_DRAW);
        gl.glVertexAttribPointer(LOC_POSITION, 3, GL.GL_FLOAT, false, 0, 0);
        gl.glEnableVertexAttribArray(LOC_POSITION);

        if (hasNormals()) {
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, ids[2]);
            float[] nrm = data.normals();
            gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) nrm.length * Buffers.SIZEOF_FLOAT,
                    Buffers.newDirectFloatBuffer(nrm), GL.GL_STATIC_DRAW);
            gl.glVertexAttribPointer(LOC_NORMAL, 3, GL.GL_FLOAT, false, 0, 0);
            gl.glEnableVertexAttribArray(LOC_NORMAL);
        }
        if (hasTexCoords()) {
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, ids[3]);
            float[] uv = data.texCoords();
            gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) uv.length * Buffers.SIZEOF_FLOAT,
                    Buffers.newDirectFloatBuffer(uv), GL.GL_STATIC_DRAW);
            gl.glVertexAttribPointer(LOC_TEXCOORD, 2, GL.GL_FLOAT, false, 0, 0);
            gl.glEnableVertexAttribArray(LOC_TEXCOORD);
        }

        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, ids[4]);
        int[] idx = data.triangleIndices();
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) idx.length * Buffers.SIZEOF_INT,
                Buffers.newDirectIntBuffer(idx), GL.GL_STATIC_DRAW);

        gl.glBindVertexArray(0);
        uploaded = true;
    }

    /**
     * Issue the indexed draw. A mesh that was never uploaded draws nothing.
     *
     * @param gl the current context's GL3-equivalent surface
     */
    public void draw(GL2ES3 gl) {
        if (!uploaded) {
            return;
        }
        gl.glBindVertexArray(ids[0]);
        gl.glDrawElements(GL.GL_TRIANGLES, data.triangleIndices().length,
                GL.GL_UNSIGNED_INT, 0);
        gl.glBindVertexArray(0);
    }

    /** Release the GPU buffers. Safe to call more than once. */
    public void dispose(GL2ES3 gl) {
        if (!uploaded) {
            return;
        }
        gl.glDeleteBuffers(4, ids, 1);
        gl.glDeleteVertexArrays(1, ids, 0);
        uploaded = false;
    }
}
