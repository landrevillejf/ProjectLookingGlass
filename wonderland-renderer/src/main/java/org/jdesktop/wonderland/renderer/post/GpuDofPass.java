/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.5: the GPU_POSTPROCESS route for depth of field -- a real
 * per-frame defocus on the live 3D canvas, in GL, with zero CPU readback.
 *
 * This is GpuHdrPass's twin for the first cosmetic link that needs a SECOND buffer:
 * the frame's own depth. It rides the exact same infrastructure the bloom and the tone
 * map proved -- the overridable Canvas3D.preSwap() the two-file source patch adds to
 * Java 3D 1.7.2, called in the SWAP handler between the glFinish and the buffer swap,
 * the one moment the just-rendered frame is complete AND still resident on the GPU --
 * but where those blit only the colour, this pass blits colour AND depth into a paired
 * attachment on its scene FBO (GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST as
 * a depth blit requires), then defocuses what the eye is not focused on in a single
 * fullscreen-triangle fragment shader and writes the result back into the very buffer
 * that gets swapped. The blurred image is what is presented; no pixel, and no depth
 * value, is read back to the CPU to compute it.
 *
 * The gather in DOF_FRAG below is not "a depth of field", it is DofKernel.gather spelled
 * in GLSL -- the same circle of confusion (clamp01 of the depth error over the focus
 * range), the same round(coc * maxRadiusPx) gather radius, the same scatter-as-gather
 * disc that SKIPS an out-of-frame or outside-the-disc sample and divides by the count it
 * kept, and the same radius-0 identity for a pixel at the focus. DofKernel is the pure
 * reference (DofKernelTest pins the focus point, the monotonic symmetric fall-off, the
 * radius cap, that a uniform frame survives any depth untouched and that a focus pixel
 * stays sharp while a far pixel blurs); this shader is the same function running per
 * frame on the live canvas. The one divergence is fixed-point: the kernel truncates each
 * channel mean to an int while the framebuffer rounds it, a sub-LSB difference at the
 * 8-bit output that every pass in this chain has against its kernel, not a different
 * defocus.
 *
 * The honest boundary, identical to the shipped bloom, tone map and anti-alias: the GL
 * methods are probe-only by nature -- a GLContext cannot be constructed in a headless
 * test JVM (the repo-wide rule for Jogamp scene-graph objects), so render()/dispose()
 * are verified by the in-JVM probe under DISPLAY, NOT asserted in a headless suite. A
 * depth blit additionally needs the host framebuffer's depth to be non-multisampled and
 * format-compatible with this pass's depth attachment; the attachment's internal format is
 * therefore queried from the target framebuffer at init (see {@link DepthBlitFormat})
 * rather than hardcoded, so a 32-bit-float JOGL default framebuffer blits as cleanly as
 * the 24-bit Java 3D one. Where the blit still faults, the pass latches off (below)
 * rather than defocusing against garbage depth. What IS pure and headless-tested
 * (GpuDofPassTest) is the
 * Settings contract, the never-throws failure latch, and that constructing a pass
 * touches no GL and treats a null context as the no-op it is on a headless box.
 *
 * The GL state bracket is not hygiene, it is the fix the bloom had to unlearn: a
 * cosmetic pass that leaves Java 3D's framebuffer/program/texture/enable bindings dirty
 * corrupts the NEXT frame's scene render to black. So saveState() snapshots the slice
 * this pass touches before the first GL call and restoreState() puts it back after the
 * composite, and the whole body is wrapped so any Throwable latches the pass off
 * (degrade to no defocus) rather than throwing into Java 3D's renderer.
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
 * The live GPU depth-of-field pass: one instance owns the FBO, the paired colour and
 * depth textures and the shader program for one canvas, lazily built on the first
 * {@link #render} and rebuilt whenever the canvas resizes. Not thread-safe -- it is
 * driven from Java 3D's single renderer thread inside preSwap(), and nowhere else.
 *
 * <p>The contract that matters to a host is the failure latch: a defocus is a cosmetic
 * pass on someone else's render thread, so a GL fault here must degrade to <em>no depth
 * of field</em> (the sharp frame is presented) and never become a second failure on top
 * of the frame the user was owed. {@link #render} catches everything, latches
 * {@link #isFailed()} and returns; every later call short-circuits.</p>
 */
public final class GpuDofPass implements GpuPostEffect {

    // GL enum values queried to snapshot/restore Java 3D's context state around the
    // defocus, as literals so no extra JOGL constant imports are needed. Same slice
    // GpuBloomPass/GpuHdrPass bracket.
    private static final int GLI_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GLI_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GLI_CURRENT_PROGRAM = 0x8B8D;
    private static final int GLI_VERTEX_ARRAY_BINDING = 0x85B5;
    private static final int GLI_ACTIVE_TEXTURE = 0x84E0;
    private static final int GLI_TEXTURE_BINDING_2D = 0x8069;
    private static final int GLI_VIEWPORT = 0x0BA2;

    private final DofKernel.Settings settings;

    // GL resources, all zero until the first render() builds them on a live ctx.
    private int sceneFbo;
    private int colorTex;
    private int depthTex;
    private int dofProg;
    private int initW;
    private int initH;
    private boolean ready;

    // The sticky failure latch: set once on any GL fault, never cleared.
    private boolean failed;
    private String failureReason;

    /** A pass with the neutral {@link DofKernel.Settings#DEFAULT} defocus. */
    public GpuDofPass() {
        this(DofKernel.Settings.DEFAULT);
    }

    /**
     * A pass with the given settings. Pure: constructing it touches no GL, so it is
     * safe to build a pass long before a canvas exists (and safe to build one in a
     * headless test, which may inspect it but must never {@link #render}).
     *
     * @param settings the defocus parameters, or {@code null} for
     *                 {@link DofKernel.Settings#DEFAULT}
     */
    public GpuDofPass(DofKernel.Settings settings) {
        this.settings = settings == null ? DofKernel.Settings.DEFAULT : settings;
    }

    /** The defocus parameters this pass gathers with. Never null. */
    public DofKernel.Settings settings() {
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
     * Run one frame of defocus against the framebuffer Java 3D is about to swap. Called
     * from {@code Canvas3D.preSwap()} with the current context; the width and height are
     * the canvas's. Does nothing (and throws nothing) when the pass has failed, the
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

            // Defocus scene -> the buffer Java 3D is about to swap.
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, targetFbo);
            gl.glDrawBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glViewport(0, 0, width, height);
            gl.glUseProgram(dofProg);
            bindTex(gl, 0, colorTex, "colorTex", dofProg);
            bindTex(gl, 1, depthTex, "depthTex", dofProg);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
            gl.glFinish();

            restoreState(gl, saved);
        } catch (Throwable t) {
            // Degrade to no defocus, never a second failure on the render thread.
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
            gl.glDeleteTextures(1, new int[] {colorTex}, 0);
            gl.glDeleteTextures(1, new int[] {depthTex}, 0);
            if (dofProg != 0) {
                gl.glDeleteProgram(dofProg);
            }
        } catch (Throwable t) {
            // A teardown fault is not worth a second failure; drop the handles.
        } finally {
            sceneFbo = 0;
            colorTex = 0;
            depthTex = 0;
            dofProg = 0;
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
        dofProg = link(gl, VERT, DOF_FRAG);
        if (dofProg == 0) {
            failed = true;
            failureReason = "shader-link-failed";
            return;
        }
        // Uniforms persist in the program object, so the settings and this size are
        // baked once here rather than re-set every frame. The focus trio selects the
        // same circle of confusion and radius DofKernel computes, so the GLSL and the
        // kernel cannot disagree.
        gl.glUseProgram(dofProg);
        gl.glUniform1f(gl.glGetUniformLocation(dofProg, "focusDistance"), settings.focusDistance());
        gl.glUniform1f(gl.glGetUniformLocation(dofProg, "focusRange"), settings.focusRange());
        gl.glUniform1f(gl.glGetUniformLocation(dofProg, "maxRadiusPx"), settings.maxRadiusPx());
        gl.glUniform2f(gl.glGetUniformLocation(dofProg, "resolution"), w, h);
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
     * The depth attachment the colour blit's depth half lands in, and the texture
     * DOF_FRAG samples for the circle of confusion. GL_NEAREST is mandatory: a depth
     * texture is a lookup, not an image, and filtering it would interpolate between two
     * surfaces that are not there. The internal format is NOT hardcoded: a depth blit
     * faults unless the destination depth format matches the source's exactly, and the
     * source is whatever depth the host framebuffer carries -- 24-bit under the reference
     * Java 3D GLFBODrawable, 32-bit float under a plain JOGL GLCanvas on Mesa.
     * {@link DepthBlitFormat#query} reads the target's own depth attachment and picks the
     * format that blits, so the same pass works on both.
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
    // Fullscreen triangle from gl_VertexID, identical to GpuBloomPass/GpuHdrPass's VERT
    // so the state bracket has nothing extra to put back. DOF_FRAG is DofKernel.gather
    // transcribed: the circle of confusion from the sampled depth, round(coc * maxRadius)
    // as the gather radius, the radius-0 identity, and a scatter-as-gather disc that
    // SKIPS an out-of-frame or outside-the-disc sample and divides by the count it kept.

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String DOF_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 frag;
            uniform sampler2D colorTex;
            uniform sampler2D depthTex;
            uniform vec2 resolution;
            uniform float focusDistance;
            uniform float focusRange;
            uniform float maxRadiusPx;

            void main() {
                vec4 centre = texture(colorTex, vUv);
                float depth = texture(depthTex, vUv).r;
                // DofKernel.circleOfConfusion: clamp01 of the depth error over the band.
                float coc = clamp(abs(depth - focusDistance) / max(focusRange, 1e-4), 0.0, 1.0);
                // DofKernel.radiusPx: Math.round for a non-negative value is floor(x + 0.5).
                int r = int(floor(coc * maxRadiusPx + 0.5));
                if (r <= 0) {
                    frag = centre;   // at the focus: the pixel keeps itself
                    return;
                }
                ivec2 px = ivec2(gl_FragCoord.xy);
                ivec2 size = ivec2(resolution);
                vec4 sum = vec4(0.0);
                int n = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int ty = px.y + dy;
                    if (ty < 0 || ty >= size.y) {
                        continue;
                    }
                    for (int dx = -r; dx <= r; dx++) {
                        if (dx * dx + dy * dy > r * r) {
                            continue;   // outside the disc, as discAverage skips it
                        }
                        int tx = px.x + dx;
                        if (tx < 0 || tx >= size.x) {
                            continue;
                        }
                        vec2 uv = (vec2(tx, ty) + 0.5) / resolution;
                        sum += texture(colorTex, uv);
                        n++;
                    }
                }
                frag = (n == 0) ? centre : sum / float(n);
            }
            """;
}
