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
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector2f;
import org.jogamp.vecmath.Vector4f;

/**
 * A GPU frosted-glass panel with anti-aliased rounded corners: a single quad
 * whose per-fragment colour and alpha are evaluated from the exact signed
 * distance to a rounded rectangle. Fragments outside the rounded silhouette are
 * discarded (so the corners are genuinely round, not a textured box), the border
 * is anti-aliased over a thin band, and a frosted band just inside the edge
 * lightens the tint and lifts its opacity toward the clear-glass centre.
 *
 * <p>This is the <strong>additive</strong> modern alternative to the 2006
 * {@link GlassyPanel}, which faked a glass bevel by tessellating a 20-vertex
 * box with a Gouraud-interpolated per-vertex colour ramp - hard, aliasing edges
 * and square corners. {@link GlassyPanel} is left untouched (it has ~65 callers);
 * widgets opt into this panel explicitly.
 *
 * <p>Like {@link SoftShadow}, it is a non-pickable {@link Shape3D} sized in place
 * via {@link #setSize(float, float)} (the vertex buffer and the {@code uHalfWin}
 * uniform carry write capability), and it needs the GLSL program from
 * {@link ShaderEffects#frostedGlassProgram()}. Because that can return
 * {@code null} when the shader resources are unavailable, construct instances
 * through {@link #create} and fall back to a {@link GlassyPanel} when it returns
 * {@code null}. A caller that adopts this as a window body (a gesture handle)
 * rather than pure decoration must call {@code setPickable(true)} on it, since
 * {@link GlassyPanel}'s pickability is what makes the frame's flip/rotate
 * gestures reachable.
 *
 * @see ShaderEffects
 * @see GlassyPanel
 */
public class FrostedGlassPanel extends Shape3D {

    /** Default tint: a cool, lightly-blue frosted white at 35% base opacity. */
    static final Color4f DEFAULT_TINT = new Color4f(0.85f, 0.90f, 1.00f, 0.35f);

    /** Frosted band width as a fraction of the corner radius. */
    private static final float EDGE_OF_RADIUS = 0.6f;

    /** Anti-alias half-width as a fraction of the panel's smaller side. */
    private static final float AA_OF_MIN_SIDE = 0.005f;

    private final float cornerRadius;
    private final float zShift;

    private final QuadArray geometry;
    private final ShaderAttributeValue halfWindow;

    /**
     * Builds a frosted-glass rounded panel with the {@link #DEFAULT_TINT}, or
     * returns {@code null} when the GPU shader program cannot be assembled so
     * the caller can fall back to a fixed-function {@link GlassyPanel}.
     *
     * @param width        panel width (world units)
     * @param height       panel height (world units)
     * @param cornerRadius rounded-corner radius (clamped to half the smaller side)
     * @param zShift       z the quad is parked at
     */
    public static FrostedGlassPanel create(float width, float height,
            float cornerRadius, float zShift) {
        return create(width, height, cornerRadius, zShift, DEFAULT_TINT);
    }

    /**
     * Builds a frosted-glass rounded panel with an explicit tint, or returns
     * {@code null} when the GPU shader program cannot be assembled so the caller
     * can fall back to a fixed-function {@link GlassyPanel}.
     *
     * @param width        panel width (world units)
     * @param height       panel height (world units)
     * @param cornerRadius rounded-corner radius (clamped to half the smaller side)
     * @param zShift       z the quad is parked at
     * @param tint         rgb glass tint, w base opacity of the clear centre
     */
    public static FrostedGlassPanel create(float width, float height,
            float cornerRadius, float zShift, Color4f tint) {
        ShaderProgram program = ShaderEffects.frostedGlassProgram();
        if (program == null) {
            return null;
        }
        return new FrostedGlassPanel(program, width, height, cornerRadius, zShift, tint);
    }

    private FrostedGlassPanel(ShaderProgram program, float width, float height,
            float cornerRadius, float zShift, Color4f tint) {
        this.cornerRadius = clampRadius(width, height, cornerRadius);
        this.zShift = zShift;

        // A decorative glass overlay: never intercept picks meant for the window
        // content behind/above it (same role SoftShadow plays for the shadow).
        setPickable(false);

        geometry = new QuadArray(4, GeometryArray.COORDINATES);
        geometry.setCapability(GeometryArray.ALLOW_COORDINATE_WRITE);
        setGeometry(geometry);

        // uHalfWin is rewritten on every setSize(), so it needs write access on
        // the live graph; radius, frost band, AA width and tint are constant.
        halfWindow = ShaderEffects.uniform("uHalfWin",
                new Vector2f(width * 0.5f, height * 0.5f), true);
        ShaderAttributeSet attrs = new ShaderAttributeSet();
        attrs.put(halfWindow);
        attrs.put(ShaderEffects.uniform("uRadius",
                Float.valueOf(this.cornerRadius), false));
        attrs.put(ShaderEffects.uniform("uEdge",
                Float.valueOf(this.cornerRadius * EDGE_OF_RADIUS), false));
        attrs.put(ShaderEffects.uniform("uAa",
                Float.valueOf(Math.min(width, height) * AA_OF_MIN_SIDE), false));
        attrs.put(ShaderEffects.uniform("uTint",
                new Vector4f(tint.x, tint.y, tint.z, tint.w), false));

        setAppearance(createAppearance(program, attrs));

        geometry.setCoordinates(0, layoutCoords(width, height, zShift));
    }

    private static Appearance createAppearance(
            ShaderProgram program, ShaderAttributeSet attrs) {
        ShaderAppearance app = new ShaderAppearance();
        app.setShaderProgram(program);
        app.setShaderAttributeSet(attrs);

        // A flipped/rotated window must still show its glass, so never cull.
        PolygonAttributes pa = new PolygonAttributes();
        pa.setCullFace(PolygonAttributes.CULL_NONE);
        app.setPolygonAttributes(pa);

        // Straight source-alpha blending: the shader outputs a tint with a
        // distance-derived alpha, so ONE_MINUS_SRC_ALPHA composites the panel
        // over whatever is behind it.
        TransparencyAttributes ta = new TransparencyAttributes(
                TransparencyAttributes.BLENDED, 1.0f);
        ta.setSrcBlendFunction(TransparencyAttributes.BLEND_SRC_ALPHA);
        ta.setDstBlendFunction(TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA);
        app.setTransparencyAttributes(ta);
        return app;
    }

    /**
     * Resizes the panel in place: rewrites the quad's vertex buffer and the
     * {@code uHalfWin} uniform. Legal on a live graph because both were built
     * with write capability. The corner radius, frost band and AA width stay at
     * their absolute world sizes, so a resized panel keeps the same look.
     */
    public void setSize(float width, float height) {
        geometry.setCoordinates(0, layoutCoords(width, height, zShift));
        halfWindow.setValue(new Vector2f(width * 0.5f, height * 0.5f));
    }

    /**
     * The corner radius this panel was built with (already clamped).
     */
    public float getCornerRadius() {
        return cornerRadius;
    }

    /**
     * Computes the four corner coordinates (x,y,z triples, counter-clockwise
     * from the -x/-y corner) of the panel quad: the full rect
     * ({@code +-width/2, +-height/2}) parked at {@code zShift}. The rounding is
     * done per fragment, so the quad itself stays the plain bounding rect. Pure
     * so the layout math is headless-testable.
     */
    static float[] layoutCoords(float width, float height, float zShift) {
        float hw = width * 0.5f;
        float hh = height * 0.5f;
        return new float[] {
            -hw, -hh, zShift,
             hw, -hh, zShift,
             hw,  hh, zShift,
            -hw,  hh, zShift,
        };
    }

    /**
     * Clamps a requested corner radius into the range the rounded-rect SDF is
     * valid for: {@code [0, min(width,height)/2]}. A radius larger than half the
     * smaller side would invert the signed-distance field (the straight edges
     * vanish and the shape degenerates), so it is capped to a stadium/round end.
     * Pure so the clamp is headless-testable.
     */
    static float clampRadius(float width, float height, float radius) {
        float max = Math.min(width, height) * 0.5f;
        return Math.max(0.0f, Math.min(radius, max));
    }
}
