/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.7: the GPU_POSTPROCESS route for screen-space ambient occlusion
 * -- a real per-frame contact darkening on the live 3D canvas, in GL, with zero CPU
 * readback.
 *
 * This is GpuDofPass's twin for the one cosmetic link that DARKENS instead of adding or
 * folding light. It rides the exact same infrastructure the bloom, tone map, defocus and
 * anti-alias proved -- the overridable Canvas3D.preSwap() the two-file source patch adds
 * to Java 3D 1.7.2, called in the SWAP handler between the glFinish and the buffer swap,
 * the one moment the just-rendered frame is complete AND still resident on the GPU -- and
 * like the defocus it blits colour AND depth into a paired attachment on its scene FBO
 * (GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST as a depth blit requires), then
 * estimates per pixel how much of the hemisphere above the surface nearby geometry blocks
 * and multiplies the composited colour by that factor, writing the result back into the
 * very buffer that gets swapped. No pixel, and no depth value, is read back to the CPU.
 *
 * The estimate in SSAO_FRAG below is not "an ambient occlusion", it is SsaoKernel spelled
 * in GLSL -- the same symmetric-perspective unprojection (near*far/(far - d*(far-near))),
 * the same gradient normal flipped toward the eye, the same fixed twelve-point hemisphere
 * (identical literals), the same integer per-pixel rotation of the tangent frame, the same
 * depth range-check, the same self-bias, and the same 1 - intensity*(occluded/12). It uses
 * texelFetch for every depth read so a sample lands on the exact texel SsaoKernel indexes,
 * which is what lets the two halves agree pixel for pixel. SsaoKernel is the pure
 * reference (SsaoKernelTest pins that a flat field is unoccluded, a pit floor beside its
 * wall is occluded while its flat middle is not, a raised platform is not, and the factor
 * stays in 0..1); this shader is the same function running per frame on the live canvas.
 *
 * The honest boundary, identical to the shipped bloom, tone map, defocus and anti-alias:
 * the GL methods are probe-only by nature -- a GLContext cannot be constructed in a
 * headless test JVM (the repo-wide rule for Jogamp scene-graph objects), so render()/
 * dispose() are verified by the in-JVM probe under DISPLAY, NOT asserted in a headless
 * suite. A depth blit additionally needs the host framebuffer's depth to be non-
 * multisampled and format-compatible with this pass's depth attachment; the attachment's
 * internal format is therefore queried from the target framebuffer at init (see
 * {@link DepthBlitFormat}) rather than hardcoded, so a 32-bit-float JOGL default
 * framebuffer blits as cleanly as the 24-bit Java 3D one. Where the blit still faults, the
 * pass latches off (below) rather than darkening against garbage depth. What IS pure and
 * headless-tested (GpuSsaoPassTest) is
 * the Settings contract, the never-throws failure latch, and that constructing a pass
 * touches no GL and treats a null context as the no-op it is on a headless box.
 *
 * The GL state bracket is not hygiene, it is the fix the bloom had to unlearn: a cosmetic
 * pass that leaves Java 3D's framebuffer/program/texture/enable bindings dirty corrupts
 * the NEXT frame's scene render to black. So saveState() snapshots the slice this pass
 * touches before the first GL call and restoreState() puts it back after the composite,
 * and the whole body is wrapped so any Throwable latches the pass off (degrade to no
 * occlusion) rather than throwing into Java 3D's renderer.
 */
package org.jdesktop.wonderland.renderer.post;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL2ES2;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLDrawable;
import com.jogamp.opengl.FBObject;
import com.jogamp.opengl.GLFBODrawable;

/**
 * The live GPU ambient-occlusion pass: one instance owns the FBO, the paired colour and
 * depth textures and the shader program for one canvas, lazily built on the first
 * {@link #render} and rebuilt whenever the canvas resizes. Not thread-safe -- it is driven
 * from Java 3D's single renderer thread inside preSwap(), and nowhere else.
 *
 * <p>The contract that matters to a host is the failure latch: an occlusion pass is a
 * cosmetic pass on someone else's render thread, so a GL fault here must degrade to
 * <em>no ambient occlusion</em> (the undarkened frame is presented) and never become a
 * second failure on top of the frame the user was owed. {@link #render} catches
 * everything, latches {@link #isFailed()} and returns; every later call short-circuits.</p>
 */
public final class GpuSsaoPass implements GpuPostEffect {

    // GL enum values queried to snapshot/restore Java 3D's context state around the
    // occlusion, as literals so no extra JOGL constant imports are needed. Same slice
    // GpuBloomPass/GpuDofPass bracket.
    private static final int GLI_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GLI_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GLI_CURRENT_PROGRAM = 0x8B8D;
    private static final int GLI_VERTEX_ARRAY_BINDING = 0x85B5;
    private static final int GLI_ACTIVE_TEXTURE = 0x84E0;
    private static final int GLI_TEXTURE_BINDING_2D = 0x8069;
    private static final int GLI_VIEWPORT = 0x0BA2;

    private final SsaoKernel.Settings settings;

    // GL resources, all zero until the first render() builds them on a live ctx.
    private int sceneFbo;
    private int colorTex;
    private int depthTex;
    private int ssaoProg;
    private int initW;
    private int initH;
    private boolean ready;

    // The sticky failure latch: set once on any GL fault, never cleared.
    private boolean failed;
    private String failureReason;

    /** A pass with the neutral {@link SsaoKernel.Settings#DEFAULT} occlusion. */
    public GpuSsaoPass() {
        this(SsaoKernel.Settings.DEFAULT);
    }

    /**
     * A pass with the given settings. Pure: constructing it touches no GL, so it is safe
     * to build a pass long before a canvas exists (and safe to build one in a headless
     * test, which may inspect it but must never {@link #render}). The settings carry the
     * camera's near/far/fov/aspect, so a host supplies the values its own view uses to
     * keep the reconstruction matched to the frame.
     *
     * @param settings the occlusion parameters, or {@code null} for
     *                 {@link SsaoKernel.Settings#DEFAULT}
     */
    public GpuSsaoPass(SsaoKernel.Settings settings) {
        this.settings = settings == null ? SsaoKernel.Settings.DEFAULT : settings;
    }

    /** The occlusion parameters this pass darkens with. Never null. */
    public SsaoKernel.Settings settings() {
        return settings;
    }

    @Override
    public boolean isFailed() {
        return failed;
    }

    /** Why {@link #isFailed()} is true, or {@code null} while the pass is healthy. */
    public String failureReason() {
        return failureReason;
    }

    /**
     * Run one frame of ambient occlusion against the framebuffer Java 3D is about to swap.
     * Called from {@code Canvas3D.preSwap()} with the current context; the width and height
     * are the canvas's. Does nothing (and throws nothing) when the pass has failed, the
     * context is null or the size is not yet positive.
     *
     * @param ctx    the live GL context, from {@code GLContext.getCurrent()}
     * @param width  the canvas width in pixels
     * @param height the canvas height in pixels
     */
    @Override
    public void render(GLContext ctx, int width, int height) {
        if (failed || ctx == null || width <= 0 || height <= 0) {
            return;
        }
        try {
            GL2 gl = ctx.getGL().getGL2();
            // Snapshot Java 3D's GL state BEFORE touching anything, so preSwap hands
            // the context back exactly as it found it.
            GLState saved = saveState(gl);
            gl.glDisable(GL.GL_DEPTH_TEST);
            gl.glDisable(GL.GL_SCISSOR_TEST);
            gl.glDisable(GL.GL_CULL_FACE);
            gl.glDisable(GL.GL_BLEND);

            // Resolve the target first: init() queries its depth attachment so the
            // depth texture it builds blits from this framebuffer's actual format.
            int targetFbo = resolveTargetFbo(ctx.getGLDrawable());

            if (!ready || width != initW || height != initH) {
                init(gl, width, height, targetFbo);
                if (failed) {
                    restoreState(gl, saved);
                    return;
                }
                initW = width;
                initH = height;
                ready = true;
            }

            // Capture the just-rendered scene AND its depth: Java 3D's target ->
            // sceneFbo. A depth blit mandates GL_NEAREST and identical src/dst
            // rectangles; the same call carries the colour, so the two stay in step.
            gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, targetFbo);
            gl.glReadBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glBindFramebuffer(GL2.GL_DRAW_FRAMEBUFFER, sceneFbo);
            gl.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT, GL.GL_NEAREST);

            // Occlude scene -> the buffer Java 3D is about to swap.
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, targetFbo);
            gl.glDrawBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glViewport(0, 0, width, height);
            gl.glUseProgram(ssaoProg);
            bindTex(gl, 0, colorTex, "colorTex", ssaoProg);
            bindTex(gl, 1, depthTex, "depthTex", ssaoProg);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
            gl.glFinish();

            restoreState(gl, saved);
        } catch (Throwable t) {
            // Degrade to no occlusion, never a second failure on the render thread.
            failed = true;
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * Free the GL resources. Best-effort and never throws; safe to call more than once and
     * safe to call before any {@link #render}.
     *
     * @param ctx the live GL context, or {@code null} to skip (nothing to free on)
     */
    @Override
    public void dispose(GLContext ctx) {
        if (ctx == null || sceneFbo == 0) {
            ready = false;
            return;
        }
        try {
            GL2 gl = ctx.getGL().getGL2();
            gl.glDeleteFramebuffers(1, new int[] {sceneFbo}, 0);
            gl.glDeleteTextures(1, new int[] {colorTex}, 0);
            gl.glDeleteTextures(1, new int[] {depthTex}, 0);
            if (ssaoProg != 0) {
                gl.glDeleteProgram(ssaoProg);
            }
        } catch (Throwable t) {
            // A teardown fault is not worth a second failure; drop the handles.
        } finally {
            sceneFbo = 0;
            colorTex = 0;
            depthTex = 0;
            ssaoProg = 0;
            ready = false;
        }
    }

    // ------------------------------------------------------------ GL internals

    /** The framebuffer Java 3D renders into and is about to swap. */
    private static int resolveTargetFbo(GLDrawable drawable) {
        if (drawable instanceof GLFBODrawable fboDrawable) {
            FBObject back = fboDrawable.getFBObject(GL.GL_BACK);
            if (back != null) {
                return back.getWriteFramebuffer();
            }
        }
        return 0;
    }

    /** Build (or rebuild after a resize) the textures, FBO and shader program. */
    private void init(GL2 gl, int w, int h, int targetFbo) {
        if (sceneFbo != 0) {
            gl.glDeleteFramebuffers(1, new int[] {sceneFbo}, 0);
            gl.glDeleteTextures(1, new int[] {colorTex}, 0);
            gl.glDeleteTextures(1, new int[] {depthTex}, 0);
        }
        colorTex = makeColorTex(gl, w, h);
        depthTex = makeDepthTex(gl, w, h, targetFbo);
        sceneFbo = makeFbo(gl, colorTex, depthTex);
        ssaoProg = link(gl, VERT, SSAO_FRAG);
        if (ssaoProg == 0) {
            failed = true;
            failureReason = "shader-link-failed";
            return;
        }
        // Uniforms persist in the program object, so the camera, the controls and this
        // size are baked once here rather than re-set every frame. The frustum values must
        // match the view that rendered the frame, or the reconstruction -- and so the
        // occlusion -- is scaled wrong; that is why the settings carry near/far/fov/aspect.
        gl.glUseProgram(ssaoProg);
        gl.glUniform1f(gl.glGetUniformLocation(ssaoProg, "near"), settings.near());
        gl.glUniform1f(gl.glGetUniformLocation(ssaoProg, "far"), settings.far());
        gl.glUniform1f(gl.glGetUniformLocation(ssaoProg, "tanHalfFovY"), settings.tanHalfFovY());
        gl.glUniform1f(gl.glGetUniformLocation(ssaoProg, "aspect"), settings.aspect());
        gl.glUniform1f(gl.glGetUniformLocation(ssaoProg, "radius"), settings.radius());
        gl.glUniform1f(gl.glGetUniformLocation(ssaoProg, "intensity"), settings.intensity());
        gl.glUniform1f(gl.glGetUniformLocation(ssaoProg, "bias"), settings.bias());
        gl.glUniform2f(gl.glGetUniformLocation(ssaoProg, "resolution"), w, h);
        gl.glUseProgram(0);
    }

    private static void bindTex(GL2 gl, int unit, int tex, String name, int prog) {
        gl.glActiveTexture(GL.GL_TEXTURE0 + unit);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glUniform1i(gl.glGetUniformLocation(prog, name), unit);
    }

    private static int makeColorTex(GL2 gl, int w, int h) {
        IntBuffer b = IntBuffer.allocate(1);
        gl.glGenTextures(1, b);
        int tex = b.get(0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL2.GL_RGBA8, w, h, 0,
                GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return tex;
    }

    /**
     * The depth attachment the colour blit's depth half lands in, and the texture SSAO_FRAG
     * texelFetches for the reconstruction. GL_NEAREST is mandatory: a depth texture is a
     * lookup, not an image, and filtering it would interpolate between two surfaces that
     * are not there -- and texelFetch needs exact texels to match SsaoKernel's indexing.
     * The internal format is NOT hardcoded: a depth blit faults unless the destination
     * depth format matches the source's exactly, and the source is whatever depth the host
     * framebuffer carries -- 24-bit under the reference Java 3D GLFBODrawable, 32-bit float
     * under a plain JOGL GLCanvas on Mesa. {@link DepthBlitFormat#query} reads the target's
     * own depth attachment and picks the format that blits, so the same pass works on both.
     */
    private static int makeDepthTex(GL2 gl, int w, int h, int targetFbo) {
        DepthBlitFormat fmt = DepthBlitFormat.query(gl, targetFbo);
        IntBuffer b = IntBuffer.allocate(1);
        gl.glGenTextures(1, b);
        int tex = b.get(0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, fmt.internalFormat, w, h, 0,
                GL2.GL_DEPTH_COMPONENT, fmt.pixelType, (ByteBuffer) null);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private static int makeFbo(GL2 gl, int colorTex, int depthTex) {
        IntBuffer b = IntBuffer.allocate(1);
        gl.glGenFramebuffers(1, b);
        int fbo = b.get(0);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo);
        gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0,
                GL.GL_TEXTURE_2D, colorTex, 0);
        gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL2.GL_DEPTH_ATTACHMENT,
                GL.GL_TEXTURE_2D, depthTex, 0);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0);
        return fbo;
    }

    private static int link(GL2 gl, String vert, String frag) {
        int v = compile(gl, GL2ES2.GL_VERTEX_SHADER, vert);
        int f = compile(gl, GL2ES2.GL_FRAGMENT_SHADER, frag);
        if (v == 0 || f == 0) {
            return 0;
        }
        int p = gl.glCreateProgram();
        gl.glAttachShader(p, v);
        gl.glAttachShader(p, f);
        gl.glLinkProgram(p);
        IntBuffer ok = IntBuffer.allocate(1);
        gl.glGetProgramiv(p, GL2ES2.GL_LINK_STATUS, ok);
        return ok.get(0) == 0 ? 0 : p;
    }

    private static int compile(GL2 gl, int type, String src) {
        int s = gl.glCreateShader(type);
        gl.glShaderSource(s, 1, new String[] {src}, (IntBuffer) null);
        gl.glCompileShader(s);
        IntBuffer ok = IntBuffer.allocate(1);
        gl.glGetShaderiv(s, GL2ES2.GL_COMPILE_STATUS, ok);
        return ok.get(0) == 0 ? 0 : s;
    }

    // ------------------------------------------------- GL state save / restore

    /**
     * The slice of GL state this pass touches. {@link #saveState} snapshots it before the
     * first GL call and {@link #restoreState} puts it back after the composite, so Java 3D's
     * renderer thread sees an unchanged context and the next frame renders.
     */
    private static final class GLState {
        int drawFbo;
        int readFbo;
        int program;
        int vao;
        int activeTexture;
        int tex0;
        final int[] viewport = new int[4];
        boolean blend;
        boolean depth;
        boolean scissor;
        boolean cull;
    }

    private static GLState saveState(GL2 gl) {
        GLState s = new GLState();
        IntBuffer ib = IntBuffer.allocate(4);
        gl.glGetIntegerv(GLI_DRAW_FRAMEBUFFER_BINDING, ib);
        s.drawFbo = ib.get(0);
        ib.rewind();
        gl.glGetIntegerv(GLI_READ_FRAMEBUFFER_BINDING, ib);
        s.readFbo = ib.get(0);
        ib.rewind();
        gl.glGetIntegerv(GLI_CURRENT_PROGRAM, ib);
        s.program = ib.get(0);
        ib.rewind();
        gl.glGetIntegerv(GLI_VERTEX_ARRAY_BINDING, ib);
        s.vao = ib.get(0);
        ib.rewind();
        gl.glGetIntegerv(GLI_ACTIVE_TEXTURE, ib);
        s.activeTexture = ib.get(0);
        ib.rewind();
        gl.glGetIntegerv(GLI_VIEWPORT, ib);
        System.arraycopy(ib.array(), 0, s.viewport, 0, 4);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        ib.rewind();
        gl.glGetIntegerv(GLI_TEXTURE_BINDING_2D, ib);
        s.tex0 = ib.get(0);
        s.blend = gl.glIsEnabled(GL.GL_BLEND);
        s.depth = gl.glIsEnabled(GL.GL_DEPTH_TEST);
        s.scissor = gl.glIsEnabled(GL.GL_SCISSOR_TEST);
        s.cull = gl.glIsEnabled(GL.GL_CULL_FACE);
        return s;
    }

    private static void restoreState(GL2 gl, GLState s) {
        gl.glUseProgram(s.program);
        gl.glBindVertexArray(s.vao);
        gl.glActiveTexture(GL.GL_TEXTURE0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, s.tex0);
        gl.glActiveTexture(s.activeTexture);
        gl.glBindFramebuffer(GL2.GL_DRAW_FRAMEBUFFER, s.drawFbo);
        gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, s.readFbo);
        gl.glViewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
        setEnable(gl, GL.GL_BLEND, s.blend);
        setEnable(gl, GL.GL_DEPTH_TEST, s.depth);
        setEnable(gl, GL.GL_SCISSOR_TEST, s.scissor);
        setEnable(gl, GL.GL_CULL_FACE, s.cull);
    }

    private static void setEnable(GL2 gl, int cap, boolean on) {
        if (on) {
            gl.glEnable(cap);
        } else {
            gl.glDisable(cap);
        }
    }

    // ------------------------------------------------------------------- GLSL
    // Fullscreen triangle from gl_VertexID, identical to the rest of the chain so the state
    // bracket has nothing extra to put back. SSAO_FRAG is SsaoKernel transcribed: the
    // perspective linearisation, the gradient normal flipped toward the eye, the fixed
    // twelve-point hemisphere, the integer per-pixel rotation, the range-check and the bias,
    // and the 1 - intensity*(occluded/12) factor the colour is multiplied by. Every depth
    // read is a texelFetch so a sample lands on the exact texel the kernel indexes.

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String SSAO_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 frag;
            uniform sampler2D colorTex;
            uniform sampler2D depthTex;
            uniform vec2 resolution;
            uniform float near;
            uniform float far;
            uniform float tanHalfFovY;
            uniform float aspect;
            uniform float radius;
            uniform float intensity;
            uniform float bias;

            const float PI = 3.141592653589793;
            // The exact twelve hemisphere samples SsaoKernel.KERNEL holds, in order.
            const vec3 KERNEL[12] = vec3[12](
                vec3( 0.0000,  0.0000,  0.3000),
                vec3( 0.5000,  0.0000,  0.4000),
                vec3( 0.0000,  0.5000,  0.4000),
                vec3(-0.5000,  0.0000,  0.4000),
                vec3( 0.0000, -0.5000,  0.4000),
                vec3( 0.3536,  0.3536,  0.5500),
                vec3(-0.3536,  0.3536,  0.5500),
                vec3(-0.3536, -0.3536,  0.5500),
                vec3( 0.3536, -0.3536,  0.5500),
                vec3( 0.7071,  0.0000,  0.7000),
                vec3( 0.0000,  0.7071,  0.7000),
                vec3(-0.7071,  0.0000,  0.7000));

            float linearZ(float d) {
                float c = clamp(d, 0.0, 1.0);
                return near * far / (far - c * (far - near));
            }
            vec3 viewPos(ivec2 p) {
                float z = linearZ(texelFetch(depthTex, p, 0).r);
                float ndcX = (float(p.x) + 0.5) / resolution.x * 2.0 - 1.0;
                float ndcY = (float(p.y) + 0.5) / resolution.y * 2.0 - 1.0;
                return vec3(ndcX * z * tanHalfFovY * aspect, ndcY * z * tanHalfFovY, z);
            }
            vec3 normalAt(ivec2 p) {
                ivec2 lo = ivec2(max(p.x - 1, 0), max(p.y - 1, 0));
                ivec2 hi = ivec2(min(p.x + 1, int(resolution.x) - 1),
                                 min(p.y + 1, int(resolution.y) - 1));
                vec3 dx = viewPos(ivec2(hi.x, p.y)) - viewPos(ivec2(lo.x, p.y));
                vec3 dy = viewPos(ivec2(p.x, hi.y)) - viewPos(ivec2(p.x, lo.y));
                vec3 c = cross(dx, dy);
                float len = length(c);
                vec3 n = (len < 1e-8) ? vec3(0.0, 0.0, -1.0) : c / len;
                return (n.z > 0.0) ? -n : n;
            }
            void main() {
                ivec2 P = ivec2(gl_FragCoord.xy);
                vec3 p = viewPos(P);
                vec3 n = normalAt(P);
                vec3 helper = (abs(n.x) > 0.5) ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 t = normalize(cross(n, helper));
                vec3 b = cross(n, t);
                // The integer per-pixel rotation SsaoKernel applies, bit for bit.
                int idx = (P.x * 7 + P.y * 13) & 7;
                float theta = float(idx) * PI / 4.0;
                float cs = cos(theta);
                float sn = sin(theta);
                vec3 tr = t * cs + b * sn;
                vec3 br = b * cs - t * sn;

                int occluded = 0;
                for (int i = 0; i < 12; i++) {
                    vec3 s = KERNEL[i];
                    vec3 o = tr * s.x + br * s.y + n * s.z;
                    vec3 sp = p + o * radius;
                    if (sp.z <= near * 0.5) {
                        continue;
                    }
                    float invZ = 1.0 / sp.z;
                    float u = (sp.x * invZ / (tanHalfFovY * aspect)) * 0.5 + 0.5;
                    float v = (sp.y * invZ / tanHalfFovY) * 0.5 + 0.5;
                    if (u < 0.0 || u >= 1.0 || v < 0.0 || v >= 1.0) {
                        continue;
                    }
                    ivec2 sc = ivec2(clamp(int(u * resolution.x), 0, int(resolution.x) - 1),
                                     clamp(int(v * resolution.y), 0, int(resolution.y) - 1));
                    float sceneZ = linearZ(texelFetch(depthTex, sc, 0).r);
                    if (abs(sceneZ - p.z) >= radius) {
                        continue;
                    }
                    if (sceneZ + bias < sp.z) {
                        occluded++;
                    }
                }
                float ao = clamp(1.0 - intensity * float(occluded) / 12.0, 0.0, 1.0);
                vec4 c = texture(colorTex, vUv);
                frag = vec4(c.rgb * ao, c.a);
            }
            """;
}
