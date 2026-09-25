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
package org.jdesktop.lg3d.scenemanager.utils.hud;

import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Container3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Point3f;
import org.jogamp.vecmath.Vector3f;

/**
 * The front-most desktop HUD layer: a screen-sized {@link Container3D} that
 * floats <em>in front of</em> every application window so transient chrome
 * (notification toasts, the window switcher, the run dialog, the desktop context
 * menu, the snap preview, the workspace pager, a brightness dim) can be drawn
 * over - and picked over - the whole desktop.
 *
 * <p>This is the deliberate counterpart of {@code WidgetLayer} (which sits
 * <em>behind</em> apps) and is the non-taskbar integration surface for the 2D
 * desktop's overlay features ported to the native 3D desktop.</p>
 *
 * <h2>Front-pose</h2>
 * <p>The view is perspective, so simply giving the layer a positive Z toward the
 * eye would magnify it and push it off the screen centre. Instead the layer is
 * lifted to {@code eye.z * }{@link #FRONT_WORLD_Z_FRACTION} and its node scale is
 * multiplied by {@code r = (eye.z - frontZ) / eye.z} (the reference plane is
 * {@code z = 0}, where application windows live). Apparent position and size go
 * as {@code world / (eyeZ - worldZ)}, so the factor {@code r} cancels the
 * perspective change exactly: the layer still spans the whole screen at its
 * natural size while sorting in front of a maximized window. This mirrors the
 * proven {@code StartMenuModel.compensatedFrontPose} technique.</p>
 *
 * <h2>Placement</h2>
 * <p>Children are positioned as a fraction of the screen - {@code fx} in
 * {@code [0,1]} left-to-right, {@code fy} in {@code [0,1]} top-to-bottom - exactly
 * like {@code WidgetLayer}, so an overlay's anchor survives resolution changes.
 * The scene origin is the centre of the screen with +x right and +y up.</p>
 */
public class DesktopHudLayer extends Container3D {

    /**
     * Fraction of the eye distance the HUD plane floats at. Matches the value
     * {@code StartMenuModel} uses to raise the start menu in front of windows:
     * far enough forward to beat a maximized window's on-axis centre in the
     * eye-distance sort, safely behind the front clip plane.
     */
    static final float FRONT_WORLD_Z_FRACTION = 0.4f;

    /** Local +z step separating stacked overlays so they sort predictably. */
    private static final float STACK_Z_STEP = 0.002f;

    /** Keeps overlays clear of the taskbar strip along the bottom edge. */
    private static final float TASKBAR_MARGIN = 0.035f;

    private float screenWidth = 1.0f;
    private float screenHeight = 1.0f;
    private int stackDepth = 0;

    public DesktopHudLayer() {
        setName("DesktopHudLayer");
        updateScreenSize();
        applyFrontPose();
    }

    /**
     * Refreshes the cached screen dimensions from {@link Toolkit3D}. Values that
     * are not yet available (canvas not realized) are ignored so a previous good
     * size is retained.
     */
    public final void updateScreenSize() {
        try {
            Toolkit3D tk = Toolkit3D.getToolkit3D();
            float w = tk.getScreenWidth();
            float h = tk.getScreenHeight();
            if (w > 0f) {
                screenWidth = w;
            }
            if (h > 0f) {
                screenHeight = h;
            }
        } catch (Throwable t) {
            // Toolkit not ready yet; keep the fallback/previous size.
        }
        setPreferredSize(new Vector3f(screenWidth, screenHeight, 0f));
    }

    /**
     * Recomputes and applies the perspective-compensated front pose from the live
     * eye position. Safe to call before the canvas is realized: on any failure the
     * layer keeps its current (identity) transform rather than being pushed to a
     * bogus depth.
     */
    public final void applyFrontPose() {
        try {
            Point3f eye = Toolkit3D.getToolkit3D()
                    .getEyePositionInVworld(new Point3f());
            float z = frontPoseZ(eye.z, FRONT_WORLD_Z_FRACTION);
            float r = frontPoseScale(eye.z, FRONT_WORLD_Z_FRACTION);
            if (Float.isNaN(z) || Float.isNaN(r)) {
                return;
            }
            setTranslation(0f, 0f, z);
            setScale(r);
        } catch (Throwable t) {
            // Canvas not ready; keep the current transform.
        }
    }

    /**
     * The world Z the HUD plane floats at for the given eye distance, or
     * {@link Float#NaN} when the inputs cannot produce a valid front plane.
     */
    static float frontPoseZ(float eyeZ, float fraction) {
        if (!(eyeZ > 0f) || !(fraction > 0f) || !(fraction < 1f)) {
            return Float.NaN;
        }
        return eyeZ * fraction;
    }

    /**
     * The node scale that cancels the perspective magnification of lifting the
     * layer from the reference plane ({@code z = 0}) to {@link #frontPoseZ}, or
     * {@link Float#NaN} when the front plane is not valid.
     */
    static float frontPoseScale(float eyeZ, float fraction) {
        float z = frontPoseZ(eyeZ, fraction);
        if (Float.isNaN(z) || (eyeZ - z) <= 0.01f) {
            return Float.NaN;
        }
        return (eyeZ - z) / eyeZ;
    }

    public float screenWidth() { return screenWidth; }
    public float screenHeight() { return screenHeight; }

    /** Converts fractional screen coords to a layer-local translation (z = 0). */
    public Vector3f fractionalToWorld(float fx, float fy, Vector3f out) {
        return fractionalToWorld(fx, fy, screenWidth, screenHeight, out);
    }

    /**
     * Pure fractional-to-local conversion for a screen of the given size. The
     * scene origin is the screen centre with +x right and +y up.
     */
    static Vector3f fractionalToWorld(float fx, float fy, float w, float h,
            Vector3f out) {
        out.set((clamp01(fx) - 0.5f) * w,
                (0.5f - clamp01(fy)) * h,
                0.0f);
        return out;
    }

    public float worldXToFraction(float worldX) {
        return clamp01(worldX / screenWidth + 0.5f);
    }

    public float worldYToFraction(float worldY) {
        return clamp01(0.5f - worldY / screenHeight);
    }

    /** Places an overlay at the given fractional screen position at stack base. */
    public void placeAt(Component3D node, float fx, float fy) {
        placeAt(node, fx, fy, 0);
    }

    /**
     * Places an overlay at the given fractional screen position, lifted by
     * {@code depth} stack steps toward the eye so concurrently visible overlays
     * sort in a predictable order (higher {@code depth} draws in front).
     */
    public void placeAt(Component3D node, float fx, float fy, int depth) {
        Vector3f v = fractionalToWorld(fx, fy, new Vector3f());
        node.setTranslation(v.x, v.y, Math.max(0, depth) * STACK_Z_STEP);
    }

    /**
     * Allocates the next stack slot and places {@code node} there, in front of any
     * overlay already showing. Call {@link #releaseStackSlot()} when it is hidden.
     */
    public int placeAtFront(Component3D node, float fx, float fy) {
        int depth = ++stackDepth;
        placeAt(node, fx, fy, depth);
        return depth;
    }

    /** Releases the most recently allocated stack slot. Never goes below zero. */
    public void releaseStackSlot() {
        if (stackDepth > 0) {
            stackDepth--;
        }
    }

    /** The current stack depth (number of allocated slots). */
    public int stackDepth() {
        return stackDepth;
    }

    /**
     * Clamps an overlay's current translation so it stays fully on screen (and
     * clear of the taskbar strip). Called after a drag or before showing.
     */
    public void clampToScreen(Component3D node) {
        Vector3f t = node.getTranslation(new Vector3f());
        Vector3f ps = node.getPreferredSize(new Vector3f());
        float halfW = Math.max(0f, ps.x * 0.5f);
        float halfH = Math.max(0f, ps.y * 0.5f);
        Vector3f c = clampTranslation(t.x, t.y, halfW, halfH,
                screenWidth, screenHeight, TASKBAR_MARGIN, new Vector3f());
        node.setTranslation(c.x, c.y, t.z);
    }

    /**
     * Pure on-screen clamp for a box of half-extents {@code (halfW, halfH)} inside
     * a screen of size {@code (w, h)} with a bottom {@code taskbarMargin}. Returns
     * {@code out} for chaining.
     */
    static Vector3f clampTranslation(float tx, float ty, float halfW, float halfH,
            float w, float h, float taskbarMargin, Vector3f out) {
        float left = -w * 0.5f + halfW;
        float right = w * 0.5f - halfW;
        float top = h * 0.5f - halfH;
        float bottom = -h * 0.5f + halfH + taskbarMargin;

        float x = (left <= right) ? clamp(tx, left, right) : 0f;
        float y = (bottom <= top) ? clamp(ty, bottom, top) : 0f;
        out.set(x, y, 0f);
        return out;
    }

    private static float clamp01(float v) {
        return (v < 0f) ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
