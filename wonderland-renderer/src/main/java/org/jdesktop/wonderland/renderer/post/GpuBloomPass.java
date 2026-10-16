/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: the GPU_POSTPROCESS route -- a real per-frame bloom
 * on the live 3D canvas, in GL, with zero CPU readback.
 *
 * This is the artefact PostProcessPlan.Path.GPU_POSTPROCESS names and the reason
 * that enum value stopped being a wish. BloomKernel is the maths, BloomPass is
 * the software seam, PostProcessPlan is the decision; GpuBloomPass is the live
 * GPU pass the decision selects when a probe proved the platform can sustain it.
 *
 * It runs from an overridable Canvas3D.preSwap() that a two-file source patch
 * adds to Java 3D 1.7.2 (lg3d-core/src/patches/java3d-core-preswap). Java 3D
 * calls preSwap() in its SWAP handler between the glFinish and the buffer swap,
 * which is the one moment the just-rendered frame is complete AND still resident
 * on the GPU -- so this pass blits that framebuffer into a texture, runs
 * bright-pass -> separable Gaussian ping-pong -> additive composite entirely in
 * GL, and composites back into the very buffer that gets swapped. The glow the
 * user sees is present in the presented frame; no pixel is ever read back to the
 * CPU to compute it. The public Canvas3D.postRender() hook cannot do this: it
 * fires in the RENDER pass before the sync, where the default framebuffer still
 * reads black and driving GL is pathological (~1 s/frame) -- WlProbe54 measured
 * exactly that, which is why the patch exists.
 *
 * PROVEN, not asserted. WlProbe53 first showed the framebuffer-blit reachable and
 * real-time; WlProbe55 ran THIS chain against the patched jar on the reference
 * card (GL 4.6 Mesa radeonsi) and measured ~14 ms/frame (71 fps) at 1280x763,
 * then read the composited buffer back once and found 7732 pixels lifted onto
 * what was dark scene -- the visible halo around the hot quad, off the buffer
 * that gets swapped. The two facts that make it work are both encoded below: the
 * hook point (preSwap, not postRender) and the GL state bracket. A bloom that
 * leaves Java 3D's framebuffer/program/texture/enable bindings dirty corrupts the
 * NEXT frame's scene render to black -- that was the first defect this pass had to
 * unlearn, so saveState()/restoreState() is not hygiene, it is the fix.
 *
 * The GL methods are probe-only by nature: a GLContext cannot be constructed in a
 * headless test JVM (the repo-wide rule for Jogamp scene-graph objects), so
 * render()/dispose() are verified by the in-JVM probe and NOT asserted here. What
 * IS pure and headless-tested is the Settings contract, the never-throws
 * failure latch and the fact that constructing a pass touches no GL.
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
import com.jogamp.opengl.util.GLBuffers;

/**
 * The live GPU bloom pass: one instance owns the FBOs, textures and shader
 * programs for one canvas, lazily built on the first {@link #render} and rebuilt
 * whenever the canvas resizes. Not thread-safe -- it is driven from Java 3D's
 * single renderer thread inside preSwap(), and nowhere else.
 *
 * <p>The contract that matters to a host is the failure latch: a bloom is a
 * cosmetic pass on someone else's render thread, so a GL fault here must degrade
 * to <em>no bloom</em> and never become a second failure on top of the frame the
 * user was owed. {@link #render} therefore catches everything, latches
 * {@link #isFailed()} and returns; every later call short-circuits. Nothing this
 * class does may throw into Java 3D's renderer.</p>
 */
public final class GpuBloomPass implements GpuPostEffect {

    /**
     * The GPU route's own parameters, in normalised 0..1 shader units -- NOT the
     * 0..255 units of {@link BloomPass.Settings}, because these feed GLSL directly
     * and the GPU blur is a fixed 9-tap separable Gaussian rather than the
     * software route's box blur of a given radius. {@link #DEFAULT} is the exact
     * constant set WlProbe55 ran when it measured 71 fps and proved the halo
     * visible, so a caller that accepts the default reproduces the verified pass.
     *
     * @param threshold  luminance the bright-pass centres its knee on (0..1)
     * @param knee       the soft shoulder width around {@code threshold} (0..1)
     * @param strength   how much blurred glow the composite adds (0 = identity)
     * @param iterations separable Gaussian ping-pong passes (0 = bright-pass only)
     */
    public record Settings(float threshold, float knee, float strength, int iterations) {

        /**
         * threshold 0.5, knee 0.25, strength 0.7, two Gaussian iterations -- the
         * WlProbe55-proven constants. A strength of 0.7 (not the software route's
         * 0.6) is what the additive GPU composite needed to read as a halo on the
         * reference card; it is a measured value, not a guess.
         */
        public static final Settings DEFAULT = new Settings(0.5f, 0.25f, 0.7f, 2);

        public Settings {
            if (threshold < 0f || knee < 0f || strength < 0f || iterations < 0) {
                throw new IllegalArgumentException(
                        "bloom settings are magnitudes, not regrets -- none may be negative");
            }
        }
    }

    // GL enum values queried to snapshot/restore Java 3D's context state around
    // the bloom, as literals so no extra JOGL constant imports are needed.
    private static final int GLI_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GLI_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GLI_CURRENT_PROGRAM = 0x8B8D;
    private static final int GLI_VERTEX_ARRAY_BINDING = 0x85B5;
    private static final int GLI_ACTIVE_TEXTURE = 0x84E0;
    private static final int GLI_TEXTURE_BINDING_2D = 0x8069;
    private static final int GLI_VIEWPORT = 0x0BA2;

    private final Settings settings;

    // GL resources, all zero until the first render() builds them on a live ctx.
    private int sceneFbo;
    private int sceneTex;
    private final int[] blurFbo = new int[2];
    private final int[] blurTex = new int[2];
    private int brightProg;
    private int blurProg;
    private int compProg;
    private int initW;
    private int initH;
    private boolean ready;

    // The sticky failure latch: set once on any GL fault, never cleared, so a
    // pass that could not run once is not retried every frame on the same fault.
    private boolean failed;
    private String failureReason;

    /** A pass with the WlProbe55-proven {@link Settings#DEFAULT}. */
    public GpuBloomPass() {
        this(Settings.DEFAULT);
    }

    /**
     * A pass with the given settings. Pure: constructing it touches no GL, so it
     * is safe to build a pass long before a canvas exists (and safe to build one
     * in a headless test, which may inspect it but must never {@link #render}).
     *
     * @param settings the parameters, or {@code null} for {@link Settings#DEFAULT}
     */
    public GpuBloomPass(Settings settings) {
        this.settings = settings == null ? Settings.DEFAULT : settings;
    }

    /** The parameters this pass blooms with. Never null. */
    public Settings settings() {
        return settings;
    }

    /**
     * Whether a GL fault latched this pass off. Once true it stays true: the pass
     * is a cosmetic extra on someone else's render thread, so a driver that
     * refused it once is not asked again, and the world renders un-bloomed rather
     * than risking a second failure per frame.
     */
    @Override
    public boolean isFailed() {
        return failed;
    }

    /** Why {@link #isFailed()} is true, or {@code null} while the pass is healthy. */
    public String failureReason() {
        return failureReason;
    }

    /**
     * Run one frame of bloom against the framebuffer Java 3D is about to swap.
     * Called from {@code Canvas3D.preSwap()} with the current context; the width
     * and height are the canvas's. Does nothing (and throws nothing) when the pass
     * has failed, the context is null or the size is not yet positive.
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
            // Snapshot Java 3D's GL state BEFORE touching anything, so preSwap
            // hands the context back exactly as it found it. Without this the
            // bloom's framebuffer/texture/program/enable changes leak into the
            // next frame's scene render and it comes out black -- the first
            // defect this pass had to unlearn.
            GLState saved = saveState(gl);
            // The passes are raw fullscreen draws: no depth, no scissor, no cull,
            // no blend. restoreState puts Java 3D's flags back afterwards.
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

            // Per Jogl2es2Pipeline.createNewContext an onscreen non-FBO drawable
            // binds NO FBO, so Java 3D rendered straight into the default
            // framebuffer's back buffer => target 0. A GLFBODrawable instead
            // renders into its GL_BACK FBObject's write framebuffer.
            int targetFbo = resolveTargetFbo(ctx.getGLDrawable());

            // Capture the just-rendered scene: Java 3D's target -> sceneFbo.
            gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, targetFbo);
            gl.glReadBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glBindFramebuffer(GL2.GL_DRAW_FRAMEBUFFER, sceneFbo);
            gl.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL.GL_COLOR_BUFFER_BIT, GL.GL_NEAREST);

            // Bright-pass: scene -> blur[0].
            pass(gl, brightProg, sceneTex, blurFbo[0], width, height, null);
            // Separable Gaussian ping-pong, H then V per iteration.
            for (int it = 0; it < settings.iterations(); it++) {
                pass(gl, blurProg, blurTex[0], blurFbo[1], width, height, 1);
                pass(gl, blurProg, blurTex[1], blurFbo[0], width, height, 0);
            }

            // Composite scene + glow -> the buffer Java 3D is about to swap.
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, targetFbo);
            gl.glDrawBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glViewport(0, 0, width, height);
            gl.glUseProgram(compProg);
            bindTex(gl, 0, sceneTex, "sceneTex", compProg);
            bindTex(gl, 1, blurTex[0], "glowTex", compProg);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
            gl.glFinish();

            // Hand Java 3D's context back untouched: the composite stays in the
            // target framebuffer, every binding/flag we changed is restored.
            restoreState(gl, saved);
        } catch (Throwable t) {
            // Degrade to no bloom, never a second failure on the render thread.
            failed = true;
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * Free the GL resources. Best-effort and never throws: a pass is torn down
     * when its canvas goes away, and a fault on the way out must not outlive it.
     * Safe to call more than once and safe to call before any {@link #render}.
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
            for (int i = 0; i < 2; i++) {
                if (blurFbo[i] != 0) {
                    gl.glDeleteFramebuffers(1, new int[] {blurFbo[i]}, 0);
                }
                if (blurTex[i] != 0) {
                    gl.glDeleteTextures(1, new int[] {blurTex[i]}, 0);
                }
            }
            if (brightProg != 0) {
                gl.glDeleteProgram(brightProg);
            }
            if (blurProg != 0) {
                gl.glDeleteProgram(blurProg);
            }
            if (compProg != 0) {
                gl.glDeleteProgram(compProg);
            }
        } catch (Throwable t) {
            // A teardown fault is not worth a second failure; drop the handles.
        } finally {
            sceneFbo = 0;
            sceneTex = 0;
            blurFbo[0] = blurFbo[1] = 0;
            blurTex[0] = blurTex[1] = 0;
            brightProg = blurProg = compProg = 0;
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

    /** Build (or rebuild after a resize) the textures, FBOs and shader programs. */
    private void init(GL2 gl, int w, int h) {
        if (sceneFbo != 0) {
            gl.glDeleteFramebuffers(1, new int[] {sceneFbo}, 0);
            gl.glDeleteTextures(1, new int[] {sceneTex}, 0);
            for (int i = 0; i < 2; i++) {
                gl.glDeleteFramebuffers(1, new int[] {blurFbo[i]}, 0);
                gl.glDeleteTextures(1, new int[] {blurTex[i]}, 0);
            }
        }
        sceneTex = makeTex(gl, w, h);
        sceneFbo = makeFbo(gl, sceneTex);
        for (int i = 0; i < 2; i++) {
            blurTex[i] = makeTex(gl, w, h);
            blurFbo[i] = makeFbo(gl, blurTex[i]);
        }
        brightProg = link(gl, VERT, BRIGHT_FRAG);
        blurProg = link(gl, VERT, BLUR_FRAG);
        compProg = link(gl, VERT, COMP_FRAG);
        if (brightProg == 0 || blurProg == 0 || compProg == 0) {
            failed = true;
            failureReason = "shader-link-failed";
            return;
        }
        // Uniforms persist in the program object, so the settings are baked once
        // here rather than re-set every frame. With Settings.DEFAULT these reduce
        // to the exact constants WlProbe55 proved.
        gl.glUseProgram(brightProg);
        gl.glUniform1f(gl.glGetUniformLocation(brightProg, "threshold"), settings.threshold());
        gl.glUniform1f(gl.glGetUniformLocation(brightProg, "knee"), settings.knee());
        gl.glUseProgram(compProg);
        gl.glUniform1f(gl.glGetUniformLocation(compProg, "strength"), settings.strength());
        gl.glUseProgram(0);
    }

    /** One fullscreen-triangle draw of {@code prog}, sampling {@code srcTex} into {@code dstFbo}. */
    private static void pass(GL2 gl, int prog, int srcTex, int dstFbo,
            int w, int h, Integer horizontal) {
        gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, dstFbo);
        gl.glViewport(0, 0, w, h);
        gl.glUseProgram(prog);
        bindTex(gl, 0, srcTex, "srcTex", prog);
        if (horizontal != null) {
            gl.glUniform1i(gl.glGetUniformLocation(prog, "horizontal"), horizontal.intValue());
        }
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
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
     * The slice of GL state the bloom chain touches. {@link #saveState} snapshots
     * it before the first GL call and {@link #restoreState} puts it back after the
     * composite, so Java 3D's renderer thread sees an unchanged context and the
     * next frame renders. This bracket is the fix for the black-frame corruption a
     * bloom that left the context dirty caused; it is not optional hygiene.
     */
    private static final class GLState {
        int drawFbo;
        int readFbo;
        int program;
        int vao;
        int activeTexture;
        int tex0;
        int tex1;
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
        gl.glActiveTexture(GL.GL_TEXTURE0 + 1);
        ib.rewind();
        gl.glGetIntegerv(GLI_TEXTURE_BINDING_2D, ib);
        s.tex1 = ib.get(0);
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
        gl.glActiveTexture(GL.GL_TEXTURE0 + 1);
        gl.glBindTexture(GL.GL_TEXTURE_2D, s.tex1);
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
    // Fullscreen triangle from gl_VertexID: no VBO, no vertex attribs, nothing
    // for the state bracket to have to put back beyond the bindings it already
    // snapshots. The bright-pass and composite read their shape from uniforms so
    // Settings.DEFAULT reduces to the exact constants WlProbe55 proved visible.

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String BRIGHT_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 frag;
            uniform sampler2D srcTex;
            uniform float threshold;
            uniform float knee;
            void main() {
                vec3 c = texture(srcTex, vUv).rgb;
                float lum = max(c.r, max(c.g, c.b));
                float k = clamp((lum - threshold) / max(knee, 1e-3), 0.0, 1.0);
                frag = vec4(c * k * k * (3.0 - 2.0 * k), 1.0);
            }
            """;

    private static final String BLUR_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 frag;
            uniform sampler2D srcTex;
            uniform int horizontal;
            void main() {
                vec2 texel = 1.0 / vec2(textureSize(srcTex, 0));
                float w0=0.227027, w1=0.1945946, w2=0.1216216, w3=0.054054, w4=0.016216;
                vec3 acc = texture(srcTex, vUv).rgb * w0;
                for (int i = 1; i < 5; i++) {
                    float wi = (i==1)?w1:(i==2)?w2:(i==3)?w3:w4;
                    vec2 off = (horizontal == 1)
                        ? vec2(texel.x * float(i), 0.0)
                        : vec2(0.0, texel.y * float(i));
                    acc += texture(srcTex, vUv + off).rgb * wi;
                    acc += texture(srcTex, vUv - off).rgb * wi;
                }
                frag = vec4(acc, 1.0);
            }
            """;

    private static final String COMP_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 frag;
            uniform sampler2D sceneTex;
            uniform sampler2D glowTex;
            uniform float strength;
            void main() {
                vec3 scene = texture(sceneTex, vUv).rgb;
                vec3 glow = texture(glowTex, vUv).rgb;
                frag = vec4(scene + glow * strength, 1.0);
            }
            """;
}
