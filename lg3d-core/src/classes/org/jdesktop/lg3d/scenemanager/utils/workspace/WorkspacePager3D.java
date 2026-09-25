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
package org.jdesktop.lg3d.scenemanager.utils.workspace;

import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.SwingNode;

/**
 * The workspace pager as a scene-graph node: the pure-Swing
 * {@link WorkspacePagerPanel} hosted on a {@link SwingNode}, so the painted strip
 * becomes a texture on a quad that the {@link WorkspacePlugin} mounts on the
 * front-most {@code DesktopHudLayer}.
 *
 * <p>Unlike the transient toast cards, the pager <em>does</em> take mouse events:
 * clicking a cell or an arrow is how the user switches workspace, so mouse
 * propagation is left off and a click on the pager does not fall through to the
 * window behind it. The size is fixed for the life of the node (the workspace
 * count never changes at runtime), which keeps the hosted texture constant.</p>
 */
public class WorkspacePager3D extends Component3D {

    private final WorkspacePagerPanel panel;
    private final SwingNode swingNode;

    /**
     * Builds a pager node for {@code registry}. The panel registers itself as a
     * registry listener, so the strip repaints on every workspace change.
     */
    public WorkspacePager3D(WorkspaceRegistry registry) {
        setName("WorkspacePager3D");
        this.panel = new WorkspacePagerPanel(registry);
        this.swingNode = new SwingNode();
        swingNode.setJPanel(panel);
        swingNode.setTransparency(0.05f);
        addChild(swingNode);
    }

    /** The hosted paint surface, exposed for the plugin's reposition/sync calls. */
    public WorkspacePagerPanel panel() {
        return panel;
    }

    /** The pager's width in world units (0 until the SwingNode has captured). */
    public float pagerWidth() {
        return swingNode.getLocalWidth();
    }

    /** The pager's height in world units (0 until the SwingNode has captured). */
    public float pagerHeight() {
        return swingNode.getLocalHeight();
    }

    /** Detaches the panel from the registry and frees the offscreen resources. */
    public void dispose() {
        panel.detach();
        swingNode.dispose();
    }

    /**
     * Pure top-left anchor: the fractional screen position that puts a node of
     * world size {@code (nodeW, nodeH)} in the top-left corner of a screen of size
     * {@code (screenW, screenH)}, inset by {@code margin}. Fractions are the
     * {@code DesktopHudLayer.placeAt} convention — 0..1 from the top-left — and are
     * clamped into range. Returns {@code {0, 0}} until the screen size is known,
     * so a caller can retry safely. Returns {@code {fx, fy}}.
     */
    static float[] topLeftFraction(float nodeW, float nodeH,
            float screenW, float screenH, float margin) {
        if (screenW <= 0f || screenH <= 0f || nodeW <= 0f || nodeH <= 0f) {
            return new float[] { 0f, 0f };
        }
        float m = Math.max(0f, margin);
        float fx = clamp01((m + nodeW * 0.5f) / screenW);
        float fy = clamp01((m + nodeH * 0.5f) / screenH);
        return new float[] { fx, fy };
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
