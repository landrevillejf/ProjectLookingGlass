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
package org.jdesktop.lg3d.scenemanager.utils.snap;

import org.jdesktop.lg3d.sg.QuadArray;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;

/**
 * The translucent quad the native 3D desktop floats over the region a dragged
 * {@link org.jdesktop.lg3d.wg.Frame3D} will snap to, so the user sees the
 * left-half / right-half / maximised target before releasing the mouse. It is
 * the {@code Frame3D} counterpart of the 2D desktop's {@code SnapPreview}
 * {@code JComponent}; the snap geometry it visualises comes from
 * {@link WindowSnap3D}, exactly as the 2D one takes its rectangle from
 * {@code WindowSnap}.
 *
 * <p>The geometry is a single unit quad (1 &times; 1, centred on its local
 * origin in the {@code z = 0} plane). A target region is shown by giving the
 * quad a non-uniform {@code setScale(width, height, 1)} and translating it to
 * the target centre, so one shape covers every snap zone without rebuilding
 * geometry. The quad is non-pickable and non-propagatable: it is purely
 * decorative and must never steal a click from the window being dragged.</p>
 *
 * <p>It is meant to be parented to the {@code DesktopHudLayer}, whose
 * perspective-compensated local space maps 1:1 onto the screen world units the
 * snap targets are expressed in, so a preview placed at a target centre/size
 * lands exactly over the region the window will fill.</p>
 */
public class SnapPreview3D extends Component3D {

    /** Fill colour of the highlight: the same blue the 2D preview uses. */
    static final float FILL_R = 80f / 255f;
    static final float FILL_G = 140f / 255f;
    static final float FILL_B = 230f / 255f;

    /** Opacity of the highlight; translucent so the window underneath shows. */
    static final float FILL_ALPHA = 0.30f;

    /**
     * Fraction of the target region left as a margin on every side, so the
     * highlight reads as an inset band (the 2D preview's {@code INSET_PX}
     * analogue) rather than butting flush against the screen edges.
     */
    static final float INSET_FRACTION = 0.01f;

    /** Local +z the quad floats at inside the HUD layer, ahead of the pager. */
    static final float PREVIEW_Z = 0.001f;

    private final Shape3D quad;

    public SnapPreview3D() {
        SimpleAppearance appearance = new SimpleAppearance(
                FILL_R, FILL_G, FILL_B, FILL_ALPHA,
                SimpleAppearance.DISABLE_CULLING);
        quad = new Shape3D(unitQuad(), appearance);
        quad.setPickable(false);
        addChild(quad);

        setPickable(false);
        setMouseEventPropagatable(false);
        setVisible(false);
    }

    /**
     * Shows the highlight over a target region of size {@code (width, height)}
     * centred at {@code (cx, cy)} in screen world units, inset by
     * {@link #INSET_FRACTION} so it reads as a band.
     */
    public void showSnap(float cx, float cy, float width, float height) {
        float w = inset(width);
        float h = inset(height);
        if (!(w > 0f) || !(h > 0f)) {
            setVisible(false);
            return;
        }
        setScale(w, h, 1.0f);
        setTranslation(cx, cy, PREVIEW_Z);
        setVisible(true);
    }

    /** Hides the highlight. */
    public void hide() {
        setVisible(false);
    }

    /** The visible width/height after applying {@link #INSET_FRACTION}. */
    static float inset(float size) {
        return size * (1.0f - INSET_FRACTION * 2.0f);
    }

    /**
     * A 1 &times; 1 quad centred on the local origin in the {@code z = 0}
     * plane, so a non-uniform scale maps directly to a target width/height.
     */
    private static QuadArray unitQuad() {
        float[] verts = {
            -0.5f, -0.5f, 0f,
             0.5f, -0.5f, 0f,
             0.5f,  0.5f, 0f,
            -0.5f,  0.5f, 0f,
        };
        QuadArray rect = new QuadArray(4, QuadArray.COORDINATES);
        rect.setCoordinates(0, verts);
        return rect;
    }
}
