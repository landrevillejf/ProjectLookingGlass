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
package org.jdesktop.lg3d.scenemanager.utils.run;

import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.SwingNode;

/**
 * The run dialog as a scene-graph node: the pure-Swing {@link RunDialogPanel}
 * hosted on a {@link SwingNode}, so the painted card becomes a texture on a quad
 * that {@link RunDialogPlugin} mounts on the front-most {@code DesktopHudLayer}.
 *
 * <p>The card takes no mouse events (the plugin drives it entirely from the
 * global key listener), so the quad is non-pickable and propagation is left on,
 * letting a click fall through to the window behind it exactly like the
 * transient toast cards and the window-switcher card.</p>
 */
public class RunDialog3D extends Component3D {

    private final RunDialogPanel panel;
    private final SwingNode swingNode;

    /**
     * Hosts {@code panel} on a fresh {@code SwingNode}. The panel's preferred
     * size never changes, so the hosted texture is a fixed size for the life of
     * the node.
     */
    public RunDialog3D(RunDialogPanel panel) {
        setName("RunDialog3D");
        this.panel = panel;
        this.swingNode = new SwingNode();
        swingNode.setJPanel(panel);
        swingNode.setTransparency(0.05f);
        addChild(swingNode);

        setPickable(false);
        setMouseEventPropagatable(true);
        setVisible(false);
    }

    /** The hosted paint surface, exposed for the plugin's key dispatch. */
    public RunDialogPanel panel() {
        return panel;
    }

    /** Shows the card (reset to an empty field and the hint status). */
    public void show() {
        panel.reset();
        setVisible(true);
    }

    /** Hides the card. */
    public void hide() {
        setVisible(false);
    }

    /** Whether the card is currently showing. */
    public boolean isShowing() {
        return isVisible();
    }

    /** Frees the offscreen Swing resources. */
    public void dispose() {
        swingNode.dispose();
    }
}
