/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * THROWAWAY DIAGNOSTIC -- not committed. Surfaces what the JOGL render path does
 * on the GL thread: the context version, whether display() runs, whether the
 * scene draw or a post-process pass throws, and what the readback holds. Delete
 * once the P2.5 probe is green.
 */
package org.jdesktop.lg3d.apps.wonderland;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.FPSAnimator;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.util.List;

import org.jdesktop.wonderland.renderer.gl.Camera;
import org.jdesktop.wonderland.renderer.gl.GlScene;
import org.jdesktop.wonderland.renderer.post.GpuPostEffect;
import org.jdesktop.wonderland.renderer.view.GlWorldBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "lg.wonderland.jogl.diag", matches = "true")
class JoglDiagTest {

    @Test
    void diagnoseAlmostEmpty() throws Exception {
        String server = WorldCatalog.defaultServer();
        String module = "wonderland-almostempty-world";
        WorldCatalog.WorldView world = WorldCatalog.loadWorld(server, module);
        GlWorldBuilder.Result built = GlWorldBuilder.build(
                WorldRender.entriesOf(server, module, world, WorldCatalog.HTTP_BYTE_FETCHER),
                WorldRender.picturesOf(server, module, world, WorldCatalog.HTTP_BYTE_FETCHER),
                640, 400);
        System.out.println("[diag] placed=" + built.placed() + " pictures="
                + built.pictures() + " skipped=" + built.skipped()
                + " sceneSize=" + built.scene().size());

        GlScene scene = built.scene();
        Camera camera = built.camera();
        String only = System.getProperty("lg.wonderland.jogl.chain", "all");
        List<GpuPostEffect> passes = switch (only) {
            case "off" -> List.of();
            case "ssao" -> org.jdesktop.wonderland.renderer.gl.GlView.buildChain(true, false, false, false, false);
            case "bloom" -> org.jdesktop.wonderland.renderer.gl.GlView.buildChain(false, true, false, false, false);
            case "hdr" -> org.jdesktop.wonderland.renderer.gl.GlView.buildChain(false, false, true, false, false);
            case "dof" -> org.jdesktop.wonderland.renderer.gl.GlView.buildChain(false, false, false, true, false);
            case "fxaa" -> org.jdesktop.wonderland.renderer.gl.GlView.buildChain(false, false, false, false, true);
            case "nossao" -> org.jdesktop.wonderland.renderer.gl.GlView.buildChain(false, true, true, true, true);
            default -> org.jdesktop.wonderland.renderer.gl.GlView.defaultChain();
        };
        System.out.println("[diag] chain=" + only + " size=" + passes.size());

        GLProfile profile = GLProfile.get(GLProfile.GL2);
        System.out.println("[diag] profile=" + profile);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(24);
        caps.setDoubleBuffered(true);
        GLCanvas canvas = new GLCanvas(caps);

        final int[] frames = {0};
        final int[] w = {0};
        final int[] h = {0};
        final BufferedImage[] shot = {null};
        canvas.addGLEventListener(new GLEventListener() {
            @Override
            public void init(GLAutoDrawable d) {
                GL3 gl = d.getGL().getGL3();
                System.out.println("[diag] init GL_VERSION=" + gl.glGetString(GL.GL_VERSION)
                        + " GL_RENDERER=" + gl.glGetString(GL.GL_RENDERER)
                        + " GLSL=" + gl.glGetString(GL2.GL_SHADING_LANGUAGE_VERSION));
                com.jogamp.opengl.GLCapabilitiesImmutable chosen = d.getChosenGLCapabilities();
                System.out.println("[diag] chosen caps depthBits=" + chosen.getDepthBits()
                        + " stencilBits=" + chosen.getStencilBits()
                        + " sampleBuffers=" + chosen.getNumSamples()
                        + " doubleBuffered=" + chosen.getDoubleBuffered());
                try {
                    scene.init(gl);
                    gl.glEnable(GL.GL_DEPTH_TEST);
                    gl.glDepthFunc(GL.GL_LEQUAL);
                    gl.glEnable(GL.GL_CULL_FACE);
                    gl.glCullFace(GL.GL_BACK);
                    System.out.println("[diag] scene.init OK");
                } catch (Throwable t) {
                    System.out.println("[diag] scene.init THREW: " + t);
                    t.printStackTrace(System.out);
                }
            }

            @Override
            public void display(GLAutoDrawable d) {
                frames[0]++;
                try {
                    GL3 gl = d.getGL().getGL3();
                    gl.glClearColor(0.05f, 0.06f, 0.08f, 1f);
                    gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
                    scene.draw(gl, camera);
                    GLContext ctx = GLContext.getCurrent();
                    for (GpuPostEffect pass : passes) {
                        if (!pass.isFailed()) {
                            pass.render(ctx, w[0], h[0]);
                        }
                    }
                    if (frames[0] == 5) {
                        for (GpuPostEffect pass : passes) {
                            System.out.println("[diag] pass " + pass.getClass().getSimpleName()
                                    + " failed=" + pass.isFailed());
                        }
                        probeDepthBlit(d.getGL().getGL2(), w[0], h[0]);
                    }
                    if (frames[0] == 30) {
                        shot[0] = readback(d.getGL().getGL2(), w[0], h[0]);
                    }
                    if (frames[0] <= 3 || frames[0] % 30 == 0) {
                        System.out.println("[diag] display #" + frames[0] + " OK "
                                + w[0] + "x" + h[0]);
                    }
                } catch (Throwable t) {
                    System.out.println("[diag] display #" + frames[0] + " THREW: " + t);
                    t.printStackTrace(System.out);
                }
            }

            @Override
            public void reshape(GLAutoDrawable d, int x, int y, int nw, int nh) {
                w[0] = nw;
                h[0] = nh;
                System.out.println("[diag] reshape " + nw + "x" + nh);
                d.getGL().getGL3().glViewport(0, 0, nw, nh);
            }

            @Override
            public void dispose(GLAutoDrawable d) {
                System.out.println("[diag] dispose");
            }
        });

        Frame frame = new Frame("diag");
        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.setSize(640, 400);
        FPSAnimator anim = new FPSAnimator(canvas, 60);
        frame.setVisible(true);
        anim.start();
        Thread.sleep(4000);
        anim.stop();
        frame.dispose();

        System.out.println("[diag] TOTAL frames=" + frames[0] + " size=" + w[0] + "x" + h[0]);
        if (shot[0] != null) {
            System.out.println("[diag] shot colours=" + WorldRender.distinctColors(shot[0])
                    + " hasColour=" + WorldRender.hasColour(shot[0])
                    + " centerRGB=" + Integer.toHexString(
                            shot[0].getRGB(shot[0].getWidth() / 2, shot[0].getHeight() / 2)));
        } else {
            System.out.println("[diag] NO shot captured by frame 30");
        }
    }

    /** Replicate SSAO/DOF's capture blit and report the GL error + a depth sample. */
    private static void probeDepthBlit(GL2 gl, int w, int h) {
        // Query the default framebuffer's own depth attachment: size + component
        // type. This is the driver-independent way a pass can pick a matching
        // depth-texture internal format instead of assuming 24.
        gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, 0);
        java.nio.IntBuffer q = java.nio.IntBuffer.allocate(1);
        gl.glGetFramebufferAttachmentParameteriv(GL2.GL_READ_FRAMEBUFFER,
                com.jogamp.opengl.GL2ES3.GL_DEPTH,
                GL2.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE, q);
        int depthSize = q.get(0);
        q.rewind();
        gl.glGetFramebufferAttachmentParameteriv(GL2.GL_READ_FRAMEBUFFER,
                com.jogamp.opengl.GL2ES3.GL_DEPTH,
                GL2.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE, q);
        int compType = q.get(0);
        System.out.println("[diag] fb0 depth attachment size=" + depthSize
                + " componentType=0x" + Integer.toHexString(compType)
                + " (GL_FLOAT=0x" + Integer.toHexString(GL2.GL_FLOAT)
                + " GL_UNSIGNED_NORMALIZED=0x"
                + Integer.toHexString(GL2.GL_UNSIGNED_NORMALIZED) + ")"
                + " err=" + gl.glGetError());
        int[] formats = {GL2.GL_DEPTH_COMPONENT16, GL2.GL_DEPTH_COMPONENT24,
                GL2.GL_DEPTH_COMPONENT32, GL2.GL_DEPTH_COMPONENT32F,
                GL2.GL_DEPTH24_STENCIL8, GL2.GL_DEPTH32F_STENCIL8};
        String[] names = {"D16", "D24", "D32", "D32F", "D24S8", "D32FS8"};
        for (int i = 0; i < formats.length; i++) {
            java.nio.IntBuffer one = java.nio.IntBuffer.allocate(1);
            gl.glGenTextures(1, one); int ctex = one.get(0);
            gl.glBindTexture(GL.GL_TEXTURE_2D, ctex);
            gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL2.GL_RGBA8, w, h, 0,
                    GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            gl.glGenTextures(1, one); int dtex = one.get(0);
            gl.glBindTexture(GL.GL_TEXTURE_2D, dtex);
            boolean stencil = (i >= 4);
            gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, formats[i], w, h, 0,
                    stencil ? GL2.GL_DEPTH_STENCIL : GL2.GL_DEPTH_COMPONENT,
                    stencil ? GL2.GL_UNSIGNED_INT_24_8 : GL.GL_UNSIGNED_INT,
                    (java.nio.ByteBuffer) null);
            gl.glGenFramebuffers(1, one); int fbo = one.get(0);
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo);
            gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0,
                    GL.GL_TEXTURE_2D, ctex, 0);
            gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER,
                    stencil ? GL2.GL_DEPTH_STENCIL_ATTACHMENT : GL2.GL_DEPTH_ATTACHMENT,
                    GL.GL_TEXTURE_2D, dtex, 0);
            int status = gl.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER);
            gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, 0);
            gl.glReadBuffer(GL2.GL_BACK);
            gl.glBindFramebuffer(GL2.GL_DRAW_FRAMEBUFFER, fbo);
            gl.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
                    GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT, GL.GL_NEAREST);
            int errBoth = gl.glGetError();
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo);
            java.nio.FloatBuffer depth = java.nio.FloatBuffer.allocate(1);
            gl.glReadPixels(w / 2, h / 2, 1, 1, GL2.GL_DEPTH_COMPONENT,
                    GL.GL_FLOAT, depth);
            gl.glGetError();
            System.out.println("[diag] depthFmt " + names[i] + " fboStatus=0x"
                    + Integer.toHexString(status) + " errColorDepth=" + errBoth
                    + " centerDepth=" + depth.get(0));
            gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0);
            gl.glDeleteFramebuffers(1, new int[] {fbo}, 0);
            gl.glDeleteTextures(1, new int[] {ctex, dtex}, 0);
        }
    }

    private static BufferedImage readback(GL2 gl, int w, int h) {
        if (gl == null || w <= 0 || h <= 0) {
            return null;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
        gl.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            buf.position((h - 1 - y) * w * 4);
            for (int x = 0; x < w; x++) {
                int r = buf.get() & 0xFF;
                int g = buf.get() & 0xFF;
                int b = buf.get() & 0xFF;
                int a = buf.get() & 0xFF;
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }
}
