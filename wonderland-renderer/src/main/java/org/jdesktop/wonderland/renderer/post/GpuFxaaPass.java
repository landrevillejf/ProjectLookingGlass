/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.4: the GPU_POSTPROCESS route for FXAA -- a real per-frame
 * image-space anti-alias on the live 3D canvas, in GL, with zero CPU readback.
 *
 * This is the third link on the preSwap post-process chain, built exactly like
 * GpuBloomPass (P3.2) and GpuHdrPass (P3.3): it rides the same patched
 * Canvas3D.preSwap() hook, blits the just-rendered framebuffer into a texture, runs a
 * single fullscreen-triangle pass over it and writes back into the very buffer that
 * gets swapped. The pass implements the SUBPIXEL stage of FXAA -- the local-contrast
 * edge gate plus the subpixel blend toward the neighbourhood average -- and the
 * FXAA_FRAG below is FxaaKernel transcribed: same Rec.709 luma, same gate, same
 * FXAA_SUBPIX_TRIM 1/4 and 1/(1-trim) scale, same clamp by the subpixel quality.
 * FxaaKernel is the pure reference (FxaaKernelTest pins the flat-area identity, the
 * gate, the weight's monotonicity and the border clamp); this shader is the same
 * function running per frame on the live canvas.
 *
 * The honest boundary, identical to the shipped bloom and tone map: the GL methods are
 * probe-only by nature -- a GLContext cannot be constructed in a headless test JVM --
 * so render()/dispose() are verified by the in-JVM probe under DISPLAY, NOT asserted
 * here. What IS pure and headless-tested (GpuFxaaPassTest) is the Settings contract,
 * the never-throws failure latch, and that a pass constructs touching no GL and treats
 * a null context as the no-op it is. The GL state bracket and the catch-everything
 * latch are the same two facts that made the bloom work: leave Java 3D's context
 * dirty and the next frame renders black; throw into preSwap and the renderer dies.
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
 * The live GPU FXAA pass: one instance owns the FBO, texture and shader program for
 * one canvas, lazily built on the first {@link #render} and rebuilt whenever the canvas
 * resizes. Not thread-safe -- driven from Java 3D's single renderer thread inside
 * preSwap(), and nowhere else.
 */
public final class GpuFxaaPass implements GpuPostEffect {

    // GL enum values queried to snapshot/restore Java 3D's context state around the
    // pass, as literals so no extra JOGL constant imports are needed.
    private static final int GLI_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GLI_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GLI_CURRENT_PROGRAM = 0x8B8D;
    private static final int GLI_VERTEX_ARRAY_BINDING = 0x85B5;
    private static final int GLI_ACTIVE_TEXTURE = 0x84E0;
    private static final int GLI_TEXTURE_BINDING_2D = 0x8069;
    private static final int GLI_VIEWPORT = 0x0BA2;

    private final FxaaKernel.Settings settings;

    private int sceneFbo;
    private int sceneTex;
    private int fxaaProg;
    private int initW;
    private int initH;
    private boolean ready;

    private boolean failed;
    private String failureReason;

    /** A pass with the FXAA 3.11 quality {@link FxaaKernel.Settings#DEFAULT}. */
    public GpuFxaaPass() {
        this(FxaaKernel.Settings.DEFAULT);
    }

    /**
     * A pass with the given settings. Pure: constructing it touches no GL, so it is
     * safe to build long before a canvas exists (and safe to build in a headless test,
     * which may inspect it but must never {@link #render}).
     *
     * @param settings the anti-aliasing parameters, or {@code null} for
     *                 {@link FxaaKernel.Settings#DEFAULT}
     */
    public GpuFxaaPass(FxaaKernel.Settings settings) {
        this.settings = settings == null ? FxaaKernel.Settings.DEFAULT : settings;
    }

    /** The anti-aliasing parameters this pass runs with. Never null. */
    public FxaaKernel.Settings settings() {
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

    @Override
    public void render(GLContext ctx, int width, int height) {
        if (failed || ctx == null || width <= 0 || height <= 0) {
            return;
        }
        try {
            GL2 gl = ctx.getGL().getGL2();
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

            gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, targetFbo);
            gl.glReadBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glBindFramebuffer(GL2.GL_DRAW_FRAMEBUFFER, sceneFbo);
            gl.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL.GL_COLOR_BUFFER_BIT, GL.GL_NEAREST);

            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, targetFbo);
            gl.glDrawBuffer(targetFbo == 0 ? GL2.GL_BACK : GL.GL_COLOR_ATTACHMENT0);
            gl.glViewport(0, 0, width, height);
            gl.glUseProgram(fxaaProg);
            bindTex(gl, 0, sceneTex, "sceneTex", fxaaProg);
            gl.glDrawArrays(GL.GL_TRIANGLES, 0, 3);
            gl.glFinish();

            restoreState(gl, saved);
        } catch (Throwable t) {
            failed = true;
            failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

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
            if (fxaaProg != 0) {
                gl.glDeleteProgram(fxaaProg);
            }
        } catch (Throwable t) {
            // A teardown fault is not worth a second failure; drop the handles.
        } finally {
            sceneFbo = 0;
            sceneTex = 0;
            fxaaProg = 0;
            ready = false;
        }
    }

    // ------------------------------------------------------------ GL internals

    private static int resolveTargetFbo(GLDrawable drawable) {
        if (drawable instanceof GLFBODrawable fboDrawable) {
            FBObject back = fboDrawable.getFBObject(GL.GL_BACK);
            if (back != null) {
                return back.getWriteFramebuffer();
            }
        }
        return 0;
    }

    private void init(GL2 gl, int w, int h) {
        if (sceneFbo != 0) {
            gl.glDeleteFramebuffers(1, new int[] {sceneFbo}, 0);
            gl.glDeleteTextures(1, new int[] {sceneTex}, 0);
        }
        sceneTex = makeTex(gl, w, h);
        sceneFbo = makeFbo(gl, sceneTex);
        fxaaProg = link(gl, VERT, FXAA_FRAG);
        if (fxaaProg == 0) {
            failed = true;
            failureReason = "shader-link-failed";
            return;
        }
        gl.glUseProgram(fxaaProg);
        gl.glUniform1f(gl.glGetUniformLocation(fxaaProg, "edgeThreshold"), settings.edgeThreshold());
        gl.glUniform1f(gl.glGetUniformLocation(fxaaProg, "edgeThresholdMin"), settings.edgeThresholdMin());
        gl.glUniform1f(gl.glGetUniformLocation(fxaaProg, "subpixQuality"), settings.subpixQuality());
        gl.glUseProgram(0);
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
    // Fullscreen triangle from gl_VertexID, identical to the other passes. FXAA_FRAG
    // is FxaaKernel transcribed: gate on the local luma contrast, then blend the
    // centre toward the four-neighbour average by the trimmed, quality-clamped weight.
    // The texture is CLAMP_TO_EDGE, so a border tap reads the frame's own edge exactly
    // as the kernel's clamped neighbour sampling does.

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FXAA_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 frag;
            uniform sampler2D sceneTex;
            uniform float edgeThreshold;
            uniform float edgeThresholdMin;
            uniform float subpixQuality;
            const float TRIM = 0.25;
            const float TRIM_SCALE = 1.0 / (1.0 - TRIM);

            float luma(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }
            void main() {
                vec2 texel = 1.0 / vec2(textureSize(sceneTex, 0));
                vec4 cC = texture(sceneTex, vUv);
                vec3 cN = texture(sceneTex, vUv + vec2(0.0, -texel.y)).rgb;
                vec3 cS = texture(sceneTex, vUv + vec2(0.0,  texel.y)).rgb;
                vec3 cE = texture(sceneTex, vUv + vec2( texel.x, 0.0)).rgb;
                vec3 cW = texture(sceneTex, vUv + vec2(-texel.x, 0.0)).rgb;
                float lC = luma(cC.rgb);
                float lN = luma(cN), lS = luma(cS), lE = luma(cE), lW = luma(cW);
                float mn = min(lC, min(min(lN, lS), min(lE, lW)));
                float mx = max(lC, max(max(lN, lS), max(lE, lW)));
                float range = mx - mn;
                // The local-contrast gate: a flat patch is returned untouched.
                if (range < max(edgeThresholdMin, mx * edgeThreshold)) {
                    frag = cC;
                    return;
                }
                float lumaL = (lN + lS + lE + lW) * 0.25;
                float rangeL = abs(lumaL - lC);
                float blend = max(0.0, (rangeL / range) - TRIM) * TRIM_SCALE;
                blend = min(subpixQuality, blend);
                vec3 rgbL = (cN + cS + cE + cW) * 0.25;
                frag = vec4(mix(cC.rgb, rgbL, blend), cC.a);
            }
            """;
}
