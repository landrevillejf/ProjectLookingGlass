/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.3: the GPU_POSTPROCESS route for HDR tone mapping -- a real
 * per-frame tone map on the live 3D canvas, in GL, with zero CPU readback.
 *
 * This is GpuBloomPass's twin for the other half of a high-dynamic-range frame. It
 * rides the exact same infrastructure the bloom proved: an overridable
 * Canvas3D.preSwap() that the two-file source patch adds to Java 3D 1.7.2, called in
 * the SWAP handler between the glFinish and the buffer swap -- the one moment the
 * just-rendered frame is complete AND still resident on the GPU. The pass blits that
 * framebuffer into a texture, folds every pixel through HdrKernel's curve in a single
 * fullscreen-triangle fragment shader, and writes the result back into the very
 * buffer that gets swapped. The tone-mapped image is what is presented; no pixel is
 * read back to the CPU to compute it.
 *
 * The curve in TONEMAP_FRAG below is not "a tone map", it is HdrKernel.curve spelled
 * in GLSL -- same exposure scale, same extended-Reinhard-with-white-point and
 * Narkowicz-ACES branches selected by `op`, same gamma encode. HdrKernel is the pure
 * reference (HdrKernelTest pins the identity, the white point, monotonicity and the
 * ACES/Reinhard split headless); this shader is the same function running per frame on
 * the live canvas. That is the same relationship BloomKernel has to GpuBloomPass.
 *
 * The honest boundary, identical to the shipped bloom: the GL methods are probe-only
 * by nature -- a GLContext cannot be constructed in a headless test JVM (the repo-wide
 * rule for Jogamp scene-graph objects), so render()/dispose() are verified by the
 * in-JVM probe under DISPLAY, NOT asserted in a headless suite. What IS pure and
 * headless-tested (GpuHdrPassTest) is the Settings contract, the never-throws failure
 * latch, and that constructing a pass touches no GL and treats a null context as the
 * no-op it is on a headless box.
 *
 * The GL state bracket is not hygiene, it is the fix the bloom had to unlearn: a
 * cosmetic pass that leaves Java 3D's framebuffer/program/texture/enable bindings
 * dirty corrupts the NEXT frame's scene render to black. So saveState() snapshots the
 * slice this pass touches before the first GL call and restoreState() puts it back
 * after the composite, and the whole body is wrapped so any Throwable latches the pass
 * off (degrade to no tone map) rather than throwing into Java 3D's renderer.
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
 * The live GPU HDR tone-mapping pass: one instance owns the FBO, texture and shader
 * program for one canvas, lazily built on the first {@link #render} and rebuilt
 * whenever the canvas resizes. Not thread-safe -- it is driven from Java 3D's single
 * renderer thread inside preSwap(), and nowhere else.
 *
 * <p>The contract that matters to a host is the failure latch: a tone map is a
 * cosmetic pass on someone else's render thread, so a GL fault here must degrade to
 * <em>no tone map</em> (the raw frame is presented) and never become a second failure
 * on top of the frame the user was owed. {@link #render} catches everything, latches
 * {@link #isFailed()} and returns; every later call short-circuits.</p>
 */
public final class GpuHdrPass implements GpuPostEffect {

    // GL enum values queried to snapshot/restore Java 3D's context state around the
    // tone map, as literals so no extra JOGL constant imports are needed. Same slice
    // GpuBloomPass brackets.
    private static final int GLI_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GLI_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GLI_CURRENT_PROGRAM = 0x8B8D;
    private static final int GLI_VERTEX_ARRAY_BINDING = 0x85B5;
    private static final int GLI_ACTIVE_TEXTURE = 0x84E0;
    private static final int GLI_TEXTURE_BINDING_2D = 0x8069;
    private static final int GLI_VIEWPORT = 0x0BA2;

    private final HdrKernel.Settings settings;

    // GL resources, all zero until the first render() builds them on a live ctx.
    private int sceneFbo;
    private int sceneTex;
    private int tonemapProg;
    private int initW;
    private int initH;
    private boolean ready;

    // The sticky failure latch: set once on any GL fault, never cleared.
    private boolean failed;
    private String failureReason;

    /** A pass with the neutral {@link HdrKernel.Settings#DEFAULT} curve. */
    public GpuHdrPass() {
        this(HdrKernel.Settings.DEFAULT);
    }

    /**
     * A pass with the given settings. Pure: constructing it touches no GL, so it is
     * safe to build a pass long before a canvas exists (and safe to build one in a
     * headless test, which may inspect it but must never {@link #render}).
     *
     * @param settings the tone-map parameters, or {@code null} for
     *                 {@link HdrKernel.Settings#DEFAULT}
     */
    public GpuHdrPass(HdrKernel.Settings settings) {
        this.settings = settings == null ? HdrKernel.Settings.DEFAULT : settings;
    }

    /** The tone-map parameters this pass folds through. Never null. */
    public HdrKernel.Settings settings() {
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
     * Run one frame of tone mapping against the framebuffer Java 3D is about to swap.
     * Called from {@code Canvas3D.preSwap()} with the current context; the width and
     * height are the canvas's. Does nothing (and throws nothing) when the pass has
     * failed, the context is null or the size is not yet positive.
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

            if (!ready || width != initW || height != initH) {
                init(gl, width, height);
                if (failed) {
                    restoreState(gl, saved);
                    return;
                }
                initW = width;
                initH = height;
                ready = true;
            }

            int targetFbo = resolveTargetFbo(ctx.getGLDrawable());

            // Capture the just-rendered scene: Java 3D's target -> sceneFbo.
            gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, targetFbo);
            gl.glReadBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glBindFramebuffer(GL2.GL_DRAW_FRAMEBUFFER, sceneFbo);
            gl.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL.GL_COLOR_BUFFER_BIT, GL.GL_NEAREST);

            // Tone map scene -> the buffer Java 3D is about to swap.
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, targetFbo);
            gl.glDrawBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glViewport(0, 0, width, height);
            gl.glUseProgram(tonemapProg);
            bindTex(gl, 0, sceneTex, "sceneTex", tonemapProg);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
            gl.glFinish();

            restoreState(gl, saved);
        } catch (Throwable t) {
            // Degrade to no tone map, never a second failure on the render thread.
            failed = true;
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * Free the GL resources. Best-effort and never throws; safe to call more than once
     * and safe to call before any {@link #render}.
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
            gl.glDeleteTextures(1, new int[] {sceneTex}, 0);
            if (tonemapProg != 0) {
                gl.glDeleteProgram(tonemapProg);
            }
        } catch (Throwable t) {
            // A teardown fault is not worth a second failure; drop the handles.
        } finally {
            sceneFbo = 0;
            sceneTex = 0;
            tonemapProg = 0;
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

    /** Build (or rebuild after a resize) the texture, FBO and shader program. */
    private void init(GL2 gl, int w, int h) {
        if (sceneFbo != 0) {
            gl.glDeleteFramebuffers(1, new int[] {sceneFbo}, 0);
            gl.glDeleteTextures(1, new int[] {sceneTex}, 0);
        }
        sceneTex = makeTex(gl, w, h);
        sceneFbo = makeFbo(gl, sceneTex);
        tonemapProg = link(gl, VERT, TONEMAP_FRAG);
        if (tonemapProg == 0) {
            failed = true;
            failureReason = "shader-link-failed";
            return;
        }
        // Uniforms persist in the program object, so the settings are baked once here
        // rather than re-set every frame. `op` selects the same three-way branch
        // HdrKernel.curve switches on, so the GLSL and the kernel cannot disagree.
        gl.glUseProgram(tonemapProg);
        gl.glUniform1i(gl.glGetUniformLocation(tonemapProg, "op"), opCode(settings.operator()));
        gl.glUniform1f(gl.glGetUniformLocation(tonemapProg, "exposure"), settings.exposure());
        gl.glUniform1f(gl.glGetUniformLocation(tonemapProg, "gamma"), settings.gamma());
        gl.glUniform1f(gl.glGetUniformLocation(tonemapProg, "whitePoint"), settings.whitePoint());
        gl.glUseProgram(0);
    }

    /** The integer the shader's {@code op} uniform switches on, matching the enum order. */
    private static int opCode(HdrKernel.Operator op) {
        return switch (op) {
            case LINEAR -> 0;
            case REINHARD -> 1;
            case ACES -> 2;
        };
    }

    private static void bindTex(GL2 gl, int unit, int tex, String name, int prog) {
        gl.glActiveTexture(GL.GL_TEXTURE0 + unit);
        gl.glBindTexture(GL.GL_TEXTURE_2D, tex);
        gl.glUniform1i(gl.glGetUniformLocation(prog, name), unit);
    }

    private static int makeTex(GL2 gl, int w, int h) {
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

    private static int makeFbo(GL2 gl, int tex) {
        IntBuffer b = IntBuffer.allocate(1);
        gl.glGenFramebuffers(1, b);
        int fbo = b.get(0);
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo);
        gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0,
                GL.GL_TEXTURE_2D, tex, 0);
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
     * The slice of GL state this pass touches. {@link #saveState} snapshots it before
     * the first GL call and {@link #restoreState} puts it back after the composite, so
     * Java 3D's renderer thread sees an unchanged context and the next frame renders.
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
    // Fullscreen triangle from gl_VertexID, identical to GpuBloomPass's VERT so the
    // state bracket has nothing extra to put back. TONEMAP_FRAG is HdrKernel.curve
    // transcribed: exposure, then the op-selected branch (extended Reinhard with a
    // white point, or Narkowicz ACES with the published constants, or identity), then
    // the gamma encode -- per channel, result clamped to 0..1.

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String TONEMAP_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 frag;
            uniform sampler2D sceneTex;
            uniform int op;            // 0 LINEAR, 1 REINHARD, 2 ACES
            uniform float exposure;
            uniform float gamma;
            uniform float whitePoint;

            float reinhard(float x, float wp) {
                float xi = max(0.0, x);
                float w = max(1e-4, wp);
                float w2 = w * w;
                return (xi * (1.0 + xi / w2)) / (1.0 + xi);
            }
            float aces(float x) {
                const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
                float xi = max(0.0, x);
                return clamp((xi * (a * xi + b)) / (xi * (c * xi + d) + e), 0.0, 1.0);
            }
            float curve(float radiance) {
                float exposed = max(0.0, radiance) * exposure;
                float mapped = (op == 1) ? reinhard(exposed, whitePoint)
                             : (op == 2) ? aces(exposed)
                             : exposed;
                return pow(clamp(mapped, 0.0, 1.0), 1.0 / gamma);
            }
            void main() {
                vec4 c = texture(sceneTex, vUv);
                frag = vec4(curve(c.r), curve(c.g), curve(c.b), c.a);
            }
            """;
}
