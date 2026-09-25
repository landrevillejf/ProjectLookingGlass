/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.shape;

import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.GeometryArray;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.QuadArray;
import org.jdesktop.lg3d.sg.ShaderAppearance;
import org.jdesktop.lg3d.sg.ShaderAttributeSet;
import org.jdesktop.lg3d.sg.ShaderAttributeValue;
import org.jdesktop.lg3d.sg.ShaderProgram;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.TransparencyAttributes;
import org.jogamp.vecmath.Vector2f;
import org.jogamp.vecmath.Vector4f;

/**
 * A GPU soft rectangular drop shadow: a single quad drawn behind a window (or
 * HUD card) whose per-fragment alpha is evaluated from the exact signed
 * distance to the window rect, ramping from {@code alpha} at the rect edge down
 * to fully transparent across a per-side penumbra. It supersedes the 2006 baked
 * {@link RectShadow}, which faked the falloff with a 28-vertex ring of
 * Gouraud-interpolated per-vertex alpha; here the falloff is smooth,
 * resolution-independent and a constant world width at any window size.
 *
 * <p>Like {@link RectShadow} this is a non-pickable {@link Shape3D} sized in
 * place via {@link #setSize(float, float)} (the vertex buffer and the
 * {@code uHalfWin} uniform both carry write capability, so a live window can be
 * resized without rebuilding the graph). Unlike {@link RectShadow} it needs the
 * GLSL program from {@link ShaderEffects#softShadowProgram()}; because that can
 * return {@code null} when the shader resources are unavailable, construct
 * instances through {@link #create} and fall back to {@link RectShadow} when it
 * returns {@code null}.
 *
 * <p>The SDF shader clamps the falloff inside the window rect to a flat
 * {@code alpha}, so {@link RectShadow}'s {@code inner} overlap margin (used only
 * to hide the seam where its ring met the opaque centre) has no meaning here and
 * is not part of this signature; the shadow quad simply spans the window rect
 * plus the outer penumbra on each side.
 *
 * @see ShaderEffects
 * @see RectShadow
 */
public class SoftShadow extends Shape3D {

    private final float north;
    private final float east;
    private final float south;
    private final float west;
    private final float zShift;

    private final QuadArray geometry;
    private final ShaderAttributeValue halfWindow;

    /**
     * Builds a soft drop shadow, or returns {@code null} when the GPU shader
     * program cannot be assembled (missing/unreadable GLSL resources) so the
     * caller can fall back to the fixed-function {@link RectShadow}.
     *
     * @param width  window width the shadow is cast by (world units)
     * @param height window height the shadow is cast by (world units)
     * @param north  penumbra width extending beyond the window's +y edge
     * @param east   penumbra width extending beyond the window's +x edge
     * @param south  penumbra width extending beyond the window's -y edge
     * @param west   penumbra width extending beyond the window's -x edge
     * @param zShift z the quad is parked at (behind the window body)
     * @param alpha  peak shadow opacity at the window edge
     */
    public static SoftShadow create(float width, float height,
            float north, float east, float south, float west,
            float zShift, float alpha) {
        ShaderProgram program = ShaderEffects.softShadowProgram();
        if (program == null) {
            return null;
        }
        return new SoftShadow(program, width, height,
                north, east, south, west, zShift, alpha);
    }

    private SoftShadow(ShaderProgram program, float width, float height,
            float north, float east, float south, float west,
            float zShift, float alpha) {
        this.north = north;
        this.east = east;
        this.south = south;
        this.west = west;
        this.zShift = zShift;

        setPickable(false);

        geometry = new QuadArray(4, GeometryArray.COORDINATES);
        geometry.setCapability(GeometryArray.ALLOW_COORDINATE_WRITE);
        setGeometry(geometry);

        // uHalfWin is rewritten on every setSize(), so it needs write access on
        // the live graph; the penumbra and alpha are constant per shadow.
        halfWindow = ShaderEffects.uniform("uHalfWin",
                new Vector2f(width * 0.5f, height * 0.5f), true);
        ShaderAttributeSet attrs = new ShaderAttributeSet();
        attrs.put(halfWindow);
        attrs.put(ShaderEffects.uniform("uSoftNESW",
                new Vector4f(north, east, south, west), false));
        attrs.put(ShaderEffects.uniform("uAlpha", Float.valueOf(alpha), false));

        setAppearance(createAppearance(program, attrs));

        geometry.setCoordinates(0,
                layoutCoords(width, height, north, east, south, west, zShift));
    }

    private static Appearance createAppearance(
            ShaderProgram program, ShaderAttributeSet attrs) {
        ShaderAppearance app = new ShaderAppearance();
        app.setShaderProgram(program);
        app.setShaderAttributeSet(attrs);

        // The quad faces the viewer but a flipped/rotated window must still cast
        // its shadow, so never cull.
        PolygonAttributes pa = new PolygonAttributes();
        pa.setCullFace(PolygonAttributes.CULL_NONE);
        app.setPolygonAttributes(pa);

        // Straight source-alpha blending: the shader outputs premultiplied-0
        // black with a distance-derived alpha, so ONE_MINUS_SRC_ALPHA over the
        // background reproduces the same composite the legacy ring relied on.
        TransparencyAttributes ta = new TransparencyAttributes(
                TransparencyAttributes.BLENDED, 1.0f);
        ta.setSrcBlendFunction(TransparencyAttributes.BLEND_SRC_ALPHA);
        ta.setDstBlendFunction(TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA);
        app.setTransparencyAttributes(ta);
        return app;
    }

    /**
     * Resizes the shadow to a new window size in place: rewrites the quad's
     * vertex buffer and the {@code uHalfWin} uniform. Legal on a live graph
     * because both were built with write capability.
     */
    public void setSize(float width, float height) {
        geometry.setCoordinates(0,
                layoutCoords(width, height, north, east, south, west, zShift));
        halfWindow.setValue(new Vector2f(width * 0.5f, height * 0.5f));
    }

    /**
     * Computes the four corner coordinates (x,y,z triples, counter-clockwise
     * from the -x/-y corner) of the shadow quad: the window rect
     * ({@code +-width/2, +-height/2}) grown outward by each side's penumbra, all
     * parked at {@code zShift}. Pure so the layout math is headless-testable.
     */
    static float[] layoutCoords(float width, float height,
            float north, float east, float south, float west, float zShift) {
        float hw = width * 0.5f;
        float hh = height * 0.5f;
        return new float[] {
            -hw - west, -hh - south, zShift,
             hw + east, -hh - south, zShift,
             hw + east,  hh + north, zShift,
            -hw - west,  hh + north, zShift,
        };
    }
}
