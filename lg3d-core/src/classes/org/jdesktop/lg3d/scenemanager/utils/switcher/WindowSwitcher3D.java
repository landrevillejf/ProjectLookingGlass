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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import java.util.List;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.SwingNode;

/**
 * The window switcher as a scene-graph node: the pure-Swing
 * {@link WindowSwitcherPanel} hosted on a {@link SwingNode}, so the painted card
 * becomes a texture on a quad that {@link WindowSwitcherPlugin} mounts on the
 * front-most {@code DesktopHudLayer}.
 *
 * <p>The switcher is purely informational while it is up (the commit is a
 * keystroke-idle timer, not a click), so it takes no mouse events: propagation
 * is left on and the quad is non-pickable, letting a click fall through to the
 * window behind it exactly like the transient toast cards.</p>
 *
 * <p>The hosted texture is a fixed size for the life of the node (the panel's
 * preferred size never changes), which keeps the {@code SwingNode} capture
 * constant while the session rows come and go.</p>
 */
public class WindowSwitcher3D extends Component3D {

    private final WindowSwitcherPanel panel;
    private final SwingNode swingNode;

    public WindowSwitcher3D() {
        setName("WindowSwitcher3D");
        this.panel = new WindowSwitcherPanel();
        this.swingNode = new SwingNode();
        swingNode.setJPanel(panel);
        swingNode.setTransparency(0.05f);
        addChild(swingNode);

        setPickable(false);
        setMouseEventPropagatable(true);
        setVisible(false);
    }

    /** The hosted paint surface, exposed for the plugin's refresh calls. */
    public WindowSwitcherPanel panel() {
        return panel;
    }

    /** Shows the card over {@code names} with {@code selected} highlighted. */
    public void show(List<String> names, int selected) {
        panel.setSession(names, selected);
        setVisible(true);
    }

    /** Hides the card. */
    public void hide() {
        panel.clear();
        setVisible(false);
    }

    /** The card's width in world units (0 until the SwingNode has captured). */
    public float switcherWidth() {
        return swingNode.getLocalWidth();
    }

    /** The card's height in world units (0 until the SwingNode has captured). */
    public float switcherHeight() {
        return swingNode.getLocalHeight();
    }

    /** Frees the offscreen Swing resources. */
    public void dispose() {
        swingNode.dispose();
    }
}
