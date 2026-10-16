/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * The depth-texture internal format a colour+depth glBlitFramebuffer will actually
 * accept, chosen from the target framebuffer's own depth attachment.
 *
 * glBlitFramebuffer's GL_DEPTH_BUFFER_BIT half faults with GL_INVALID_OPERATION -- and
 * silently copies nothing -- unless the destination depth attachment's internal format
 * matches the source's exactly. GpuSsaoPass and GpuDofPass both blit the just-rendered
 * scene's depth into a texture they own so their shaders can texelFetch it for the
 * reconstruction, and both used to hardcode GL_DEPTH_COMPONENT24: correct for the 24-bit
 * depth the reference Java 3D GLFBODrawable gave them, but a plain JOGL GLCanvas on a
 * modern Mesa driver defaults to a 32-bit FLOAT depth, against which the hardcoded 24-bit
 * blit faults and the depth texture stays all-zero. A zeroed depth reads as "every pixel
 * maximally occluded" to SSAO and "every pixel maximally far" to DOF, so the whole frame
 * blackens -- the exact failure the P2.5 JOGL probe caught before a single java3d file was
 * cut over.
 *
 * So the format is a decision over two numbers the driver reports for the target
 * framebuffer -- its depth attachment's bit size and its component type -- not a constant.
 * The split mirrors the passes themselves and their pure kernel references (SsaoKernel,
 * DofKernel): choose() is the pure decision, asserted headless by DepthBlitFormatTest;
 * query() is the thin GL probe that reads the two numbers and is probe-only for the same
 * reason every Gpu*Pass GL method is (a GLContext cannot be constructed without a
 * display). One copy of the query serves both passes, so the added probe-only surface is
 * a single method rather than two.
 */
package org.jdesktop.wonderland.renderer.post;

import java.nio.IntBuffer;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL2ES3;

/**
 * The depth internal format (and the {@code glTexImage2D} pixel type that pairs with it)
 * whose blit from a given target framebuffer will succeed. Immutable; obtained from
 * {@link #query} on a live context or from the pure {@link #choose} in a test.
 */
final class DepthBlitFormat {

    /** The depth internal format, e.g. {@link GL2ES3#GL_DEPTH_COMPONENT32F}. */
    final int internalFormat;

    /** The {@code glTexImage2D} pixel type that pairs with {@link #internalFormat}. */
    final int pixelType;

    private DepthBlitFormat(int internalFormat, int pixelType) {
        this.internalFormat = internalFormat;
        this.pixelType = pixelType;
    }

    /**
     * Read the target framebuffer's depth attachment and choose the format that blits from
     * it. Binds {@code GL_READ_FRAMEBUFFER} to {@code targetFbo} -- callers run this inside
     * their own GL state save/restore bracket, so the binding is put back for them. A query
     * the driver refuses (or a target with no depth) leaves the size at zero, and
     * {@link #choose} then falls back to the 24-bit default the passes always used, so this
     * degrades to the pre-existing behaviour rather than throwing.
     *
     * @param gl        the live GL context's GL2
     * @param targetFbo the framebuffer the pass blits <em>from</em>; {@code 0} is the
     *                  default (window) framebuffer
     * @return the matching format; never null
     */
    static DepthBlitFormat query(GL2 gl, int targetFbo) {
        int depthSize = 0;
        int componentType = 0;
        try {
            gl.glBindFramebuffer(GL2.GL_READ_FRAMEBUFFER, targetFbo);
            // The default framebuffer names its depth attachment GL_DEPTH; a real FBO
            // names it GL_DEPTH_ATTACHMENT. Querying with the wrong one faults.
            int attachment = targetFbo == 0 ? GL2ES3.GL_DEPTH : GL2.GL_DEPTH_ATTACHMENT;
            IntBuffer q = IntBuffer.allocate(1);
            gl.glGetFramebufferAttachmentParameteriv(GL2.GL_READ_FRAMEBUFFER, attachment,
                    GL2.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE, q);
            depthSize = q.get(0);
            q.rewind();
            gl.glGetFramebufferAttachmentParameteriv(GL2.GL_READ_FRAMEBUFFER, attachment,
                    GL2.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE, q);
            componentType = q.get(0);
            // Clear any error the queries raised so the caller's own GL checks stay honest.
            gl.glGetError();
        } catch (Throwable t) {
            // A refused query leaves depthSize 0; choose() falls back to 24-bit.
        }
        return choose(depthSize, componentType);
    }

    /**
     * The format whose blit from a framebuffer reporting the given depth attachment will
     * succeed. A 32-bit float depth needs the float format; a normalised depth takes the
     * widest integer format its size allows; anything that reports no usable depth falls
     * back to the 24-bit default, which is at worst the pre-existing behaviour.
     *
     * @param depthSize     {@code GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE} of the target, in bits
     * @param componentType {@code GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE} of the target
     * @return the matching internal format and its pixel type; never null
     */
    static DepthBlitFormat choose(int depthSize, int componentType) {
        if (componentType == GL2.GL_FLOAT && depthSize >= 32) {
            return new DepthBlitFormat(GL2ES3.GL_DEPTH_COMPONENT32F, GL2.GL_FLOAT);
        }
        if (depthSize >= 32) {
            return new DepthBlitFormat(GL2.GL_DEPTH_COMPONENT32, GL2.GL_UNSIGNED_INT);
        }
        if (depthSize >= 24) {
            return new DepthBlitFormat(GL2.GL_DEPTH_COMPONENT24, GL2.GL_UNSIGNED_INT);
        }
        if (depthSize >= 16) {
            return new DepthBlitFormat(GL2.GL_DEPTH_COMPONENT16, GL2.GL_UNSIGNED_SHORT);
        }
        return new DepthBlitFormat(GL2.GL_DEPTH_COMPONENT24, GL2.GL_UNSIGNED_INT);
    }
}
