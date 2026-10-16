/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: the JOGL scene -- a draw list plus one GLSL program.
 *
 * GlScene owns the shader program and the ordered set of (mesh, model-matrix)
 * pairs that make up a world, and draws them all under a Camera. The program is
 * deliberately minimal (position/normal/texcoord in, a Lambert term and a base
 * colour out) so it links on any GL3 driver; the richer PBR / sky / shadow /
 * water shading the roadmap describes is layered on top of this same draw list
 * once the scene core is proven, and the post-process chain (the already-JOGL
 * Gpu*Pass set) runs after GlScene.draw into the same framebuffer.
 *
 * Attribute locations are bound explicitly (glBindAttribLocation) to the fixed
 * 0/1/2 GlMesh uploads through, so the GLSL needs no layout qualifiers and the
 * two classes cannot drift apart.
 */
package org.jdesktop.wonderland.renderer.gl;

import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;
import java.util.ArrayList;
import java.util.List;

/**
 * An ordered set of GPU meshes drawn with one program under a {@link Camera}.
 */
public final class GlScene {

    private static final String VERTEX =
            "#version 330 core\n"
          + "in vec3 aPosition;\n"
          + "in vec3 aNormal;\n"
          + "in vec2 aTexCoord;\n"
          + "uniform mat4 uModel;\n"
          + "uniform mat4 uView;\n"
          + "uniform mat4 uProjection;\n"
          + "out vec3 vNormal;\n"
          + "out vec2 vTexCoord;\n"
          + "void main() {\n"
          + "    vNormal = mat3(uModel) * aNormal;\n"
          + "    vTexCoord = aTexCoord;\n"
          + "    gl_Position = uProjection * uView * uModel * vec4(aPosition, 1.0);\n"
          + "}\n";

    private static final String FRAGMENT =
            "#version 330 core\n"
          + "in vec3 vNormal;\n"
          + "in vec2 vTexCoord;\n"
          + "uniform vec3 uBaseColor;\n"
          + "out vec4 fragColor;\n"
          + "void main() {\n"
          + "    vec3 n = normalize(vNormal);\n"
          + "    float lambert = clamp(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0, 1.0);\n"
          + "    vec3 lit = uBaseColor * (0.35 + 0.65 * lambert);\n"
          + "    fragColor = vec4(lit, 1.0);\n"
          + "}\n";

    private static final class Entry {
        final GlMesh mesh;
        final float[] model;
        Entry(GlMesh mesh, float[] model) {
            this.mesh = mesh;
            this.model = model;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int program;
    private int locModel;
    private int locView;
    private int locProjection;
    private int locBaseColor;
    private boolean linked;

    /** Add a mesh with its world (model) matrix, column-major float[16]. */
    public void add(GlMesh mesh, float[] model) {
        if (mesh == null) {
            throw new IllegalArgumentException("mesh must not be null");
        }
        if (model == null || model.length != 16) {
            throw new IllegalArgumentException("model must be a float[16]");
        }
        entries.add(new Entry(mesh, model.clone()));
    }

    /** The number of meshes in the draw list. */
    public int size() {
        return entries.size();
    }

    /** Whether {@link #init} linked the program successfully. */
    public boolean isLinked() {
        return linked;
    }

    /**
     * Compile and link the program and bind the fixed attribute locations.
     *
     * @param gl the current context's GL3 surface
     * @return true when the program linked; false (and no draw) otherwise
     */
    public boolean init(GL3 gl) {
        if (linked) {
            return true;
        }
        int vs = compile(gl, GL2ES3.GL_VERTEX_SHADER, VERTEX);
        int fs = compile(gl, GL2ES3.GL_FRAGMENT_SHADER, FRAGMENT);
        if (vs == 0 || fs == 0) {
            return false;
        }
        program = gl.glCreateProgram();
        gl.glAttachShader(program, vs);
        gl.glAttachShader(program, fs);
        gl.glBindAttribLocation(program, GlMesh.LOC_POSITION, "aPosition");
        gl.glBindAttribLocation(program, GlMesh.LOC_NORMAL, "aNormal");
        gl.glBindAttribLocation(program, GlMesh.LOC_TEXCOORD, "aTexCoord");
        gl.glLinkProgram(program);
        int[] status = new int[1];
        gl.glGetProgramiv(program, GL2ES3.GL_LINK_STATUS, status, 0);
        gl.glDeleteShader(vs);
        gl.glDeleteShader(fs);
        if (status[0] == 0) {
            gl.glDeleteProgram(program);
            program = 0;
            return false;
        }
        locModel = gl.glGetUniformLocation(program, "uModel");
        locView = gl.glGetUniformLocation(program, "uView");
        locProjection = gl.glGetUniformLocation(program, "uProjection");
        locBaseColor = gl.glGetUniformLocation(program, "uBaseColor");
        linked = true;
        return true;
    }

    /**
     * Upload every mesh that is not on the GPU yet and draw the whole list.
     *
     * @param gl     the current context's GL3 surface
     * @param camera the eye/lens to draw under
     */
    public void draw(GL3 gl, Camera camera) {
        if (!linked) {
            return;
        }
        gl.glUseProgram(program);
        gl.glUniformMatrix4fv(locView, 1, false, camera.view(), 0);
        gl.glUniformMatrix4fv(locProjection, 1, false, camera.projection(), 0);
        gl.glUniform3f(locBaseColor, 0.75f, 0.72f, 0.68f);
        for (Entry e : entries) {
            e.mesh.upload(gl);
            gl.glUniformMatrix4fv(locModel, 1, false, e.model, 0);
            e.mesh.draw(gl);
        }
        gl.glUseProgram(0);
    }

    /** Release the program. Meshes dispose their own buffers. */
    public void dispose(GL3 gl) {
        if (linked) {
            gl.glDeleteProgram(program);
            program = 0;
            linked = false;
        }
    }

    private static int compile(GL3 gl, int type, String source) {
        int shader = gl.glCreateShader(type);
        if (shader == 0) {
            return 0;
        }
        gl.glShaderSource(shader, 1, new String[] { source },
                new int[] { source.length() }, 0);
        gl.glCompileShader(shader);
        int[] status = new int[1];
        gl.glGetShaderiv(shader, GL2ES3.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            gl.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
