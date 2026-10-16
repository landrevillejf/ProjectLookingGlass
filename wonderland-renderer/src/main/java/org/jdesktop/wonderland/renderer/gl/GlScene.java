/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: the JOGL scene -- a draw list plus one GLSL program.
 *
 * GlScene owns the shader program and the ordered set of (mesh, model-matrix)
 * pairs that make up a world, and draws them all under a Camera. The program is
 * deliberately minimal (position/normal/texcoord in; a Lambert term, a base
 * colour and an optional diffuse texture out) so it links on any GL3 driver; the
 * richer PBR / sky / shadow / water shading the roadmap describes is layered on
 * top of this same draw list once the scene core is proven, and the post-process
 * chain (the already-JOGL Gpu*Pass set) runs after GlScene.draw into the same
 * framebuffer. A textured shape modulates its texture by the base colour, exactly
 * as the java3d path kept the diffuse colour as a tint over the texture.
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
          + "uniform vec4 uBaseColor;\n"
          + "uniform sampler2D uTexture;\n"
          + "uniform int uUseTexture;\n"
          + "out vec4 fragColor;\n"
          + "void main() {\n"
          + "    vec3 n = normalize(vNormal);\n"
          + "    float lambert = clamp(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0, 1.0);\n"
          + "    vec3 base = uBaseColor.rgb;\n"
          + "    if (uUseTexture == 1) {\n"
          + "        base = texture(uTexture, vTexCoord).rgb * base;\n"
          + "    }\n"
          + "    vec3 lit = base * (0.35 + 0.65 * lambert);\n"
          + "    fragColor = vec4(lit, uBaseColor.a);\n"
          + "}\n";

    private static final class Entry {
        final GlMesh mesh;
        final float[] model;
        final float[] baseColor;
        final byte[] texture;
        int texId;
        boolean texTried;
        Entry(GlMesh mesh, float[] model, float[] baseColor, byte[] texture) {
            this.mesh = mesh;
            this.model = model;
            this.baseColor = baseColor;
            this.texture = texture;
        }
    }

    /** The base colour a mesh with no material of its own is drawn in. */
    public static final float[] DEFAULT_BASE_COLOR = {0.75f, 0.72f, 0.68f, 1.0f};

    private final List<Entry> entries = new ArrayList<>();
    private int program;
    private int locModel;
    private int locView;
    private int locProjection;
    private int locBaseColor;
    private int locTexture;
    private int locUseTexture;
    private boolean linked;

    /** Add a mesh with its world (model) matrix, drawn in {@link #DEFAULT_BASE_COLOR}. */
    public void add(GlMesh mesh, float[] model) {
        add(mesh, model, null, null);
    }

    /**
     * Add a mesh with its world (model) matrix, base colour and optional diffuse
     * texture. The colour is the material's diffuse rgba (a MaterialAppearance's
     * four components); the texture is the encoded diffuse image the binding plan
     * resolved and the store fetched, or {@code null} to draw the shape untextured.
     *
     * @param mesh      the GPU mesh; not null
     * @param model     the world matrix, column-major float[16]; not null
     * @param baseColor rgba in 0..1, or {@code null} for {@link #DEFAULT_BASE_COLOR}
     * @param texture   encoded image bytes, or {@code null} for no texture
     */
    public void add(GlMesh mesh, float[] model, float[] baseColor, byte[] texture) {
        if (mesh == null) {
            throw new IllegalArgumentException("mesh must not be null");
        }
        if (model == null || model.length != 16) {
            throw new IllegalArgumentException("model must be a float[16]");
        }
        entries.add(new Entry(mesh, model.clone(), sanitizeColor(baseColor), texture));
    }

    /**
     * Coerce a caller's rgba to a non-null float[4]: {@code null} or an array
     * shorter than three falls back to {@link #DEFAULT_BASE_COLOR}, and a missing
     * alpha becomes opaque. Pure, so the material feed is asserted without a GL
     * context.
     */
    static float[] sanitizeColor(float[] rgba) {
        if (rgba == null || rgba.length < 3) {
            return DEFAULT_BASE_COLOR.clone();
        }
        float[] out = new float[4];
        out[0] = rgba[0];
        out[1] = rgba[1];
        out[2] = rgba[2];
        out[3] = rgba.length >= 4 ? rgba[3] : 1.0f;
        return out;
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
        locTexture = gl.glGetUniformLocation(program, "uTexture");
        locUseTexture = gl.glGetUniformLocation(program, "uUseTexture");
        // The diffuse texture is sampled from unit 0; bind the sampler once.
        gl.glUseProgram(program);
        gl.glUniform1i(locTexture, 0);
        gl.glUseProgram(0);
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
        for (Entry e : entries) {
            e.mesh.upload(gl);
            gl.glUniformMatrix4fv(locModel, 1, false, e.model, 0);
            gl.glUniform4fv(locBaseColor, 1, e.baseColor, 0);
            bindTexture(gl, e);
            e.mesh.draw(gl);
        }
        gl.glUseProgram(0);
    }

    /**
     * Bind an entry's diffuse texture to unit 0, uploading it once on first use,
     * and tell the shader whether to sample it. A texture that fails to upload
     * (GlTexture returns 0) is remembered as tried, so the shape renders
     * untextured rather than retrying the decode every frame.
     */
    private void bindTexture(GL3 gl, Entry e) {
        if (e.texture == null) {
            gl.glUniform1i(locUseTexture, 0);
            return;
        }
        if (!e.texTried) {
            e.texId = GlTexture.upload(gl, e.texture);
            e.texTried = true;
        }
        if (e.texId == 0) {
            gl.glUniform1i(locUseTexture, 0);
            return;
        }
        gl.glActiveTexture(GL2ES3.GL_TEXTURE0);
        gl.glBindTexture(GL2ES3.GL_TEXTURE_2D, e.texId);
        gl.glUniform1i(locUseTexture, 1);
    }

    /** Release the program and every uploaded texture. Meshes dispose their own buffers. */
    public void dispose(GL3 gl) {
        for (Entry e : entries) {
            GlTexture.dispose(gl, e.texId);
            e.texId = 0;
            e.texTried = false;
        }
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
