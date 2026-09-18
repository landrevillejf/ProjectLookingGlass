/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.controlcenter;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.apps.uikit.Button3D;
import org.jdesktop.lg3d.apps.uikit.Ui3D;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.Taskbar;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * The Control Center application window: a {@link Frame3D} with a 100%
 * lg3d-native 3D shell (no SwingNode) - a category navigation column on the
 * left and the selected {@link ControlPanel} page on the right.
 *
 * <pre>
 *   +-------------------------------------------------------------+
 *   | Control Center                            [ _ ][ X ] (deco) |
 *   | +----------+------------------------------------------------+
 *   | | Display  |  (page content, built by the ControlPanel)     |
 *   | | Users    |                                                |
 *   | | System   |                                                |
 *   | |Appearance|                                                |
 *   | +----------+------------------------------------------------+
 *   +-------------------------------------------------------------+
 * </pre>
 *
 * <p>Pages are discovered from {@link ControlPanelRegistry} and notified via
 * {@code onShow}/{@code onHide} as they become visible or are hidden, so a
 * page only polls its system service while it is actually on screen. The
 * frame forwards its own enabled/visible transitions to the current page, so
 * minimizing or closing the window stops every timer too.</p>
 */
public class ControlCenterFrame3D extends Frame3D {

    private static final Color4f WINDOW_BG = new Color4f(0.05f, 0.07f, 0.11f, 0.55f);

    private final List<ControlPanel> panels = new ArrayList<>();
    private final List<Component3D> pages = new ArrayList<>();
    private final List<Button3D> tabs = new ArrayList<>();
    private final Component3D pageHost = new Component3D();

    private int current = -1;
    private boolean live = true;

    public ControlCenterFrame3D() {
        setName("Control Center");

        // Match the window aspect to the usable screen area (above the
        // taskbar) so the decoration's aspect-preserving maximize fills the
        // viewport on both axes.
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float usableH = tk.getScreenHeight() - Taskbar.getReservedBottomHeight();
        float H = usableH * 0.58f;
        float W = H * tk.getScreenWidth() / usableH;
        setPreferredSize(new Vector3f(W, H, 0.01f));

        // ---- layout metrics (origin at window centre, +x right, +y up) ----
        float pad = Math.min(W, H) * 0.022f;
        float topMargin = H * 0.075f;   // clear of the decoration buttons

        float contentTop = H * 0.5f - topMargin;
        float contentBottom = -H * 0.5f + pad;
        float contentH = contentTop - contentBottom;
        float contentCY = (contentTop + contentBottom) * 0.5f;
        float xLeft = -W * 0.5f + pad;

        float navW = W * 0.2f;
        float pageW = W - navW - 3 * pad;
        float navCX = xLeft + navW * 0.5f;
        float pageCX = xLeft + navW + pad + pageW * 0.5f;

        // ---- window backdrop + title ----
        addChild(Ui3D.component(
                Ui3D.at(Ui3D.panel(W, H, 0.006f, WINDOW_BG), 0f, 0f, -0.008f)));
        float titleH = topMargin * 0.46f;
        addChild(Ui3D.component(Ui3D.label("Control Center", W * 0.5f, titleH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT,
                xLeft, H * 0.5f - topMargin * 0.62f, 0.002f)));

        // ---- navigation column + pages ----
        panels.addAll(ControlPanelRegistry.panels());
        float slot = contentH / panels.size();
        float tabH = Math.min(slot * 0.78f, H * 0.1f);
        float tabTextH = tabH * 0.4f;

        pageHost.setTranslation(pageCX, contentCY, 0.002f);
        addChild(pageHost);

        for (int i = 0; i < panels.size(); i++) {
            ControlPanel p = panels.get(i);
            Component3D page = p.component(pageW, contentH);
            pages.add(page);

            final int idx = i;
            Button3D tab = new Button3D(p.displayName(), navW, tabH, tabTextH,
                    Ui3D.TAB_OFF, Ui3D.TAB_ON, Ui3D.TEXT_BRIGHT,
                    new ActionNoArg() {
                        public void performAction(LgEventSource s) {
                            select(idx);
                        }
                    });
            tab.setTranslation(navCX, contentTop - (i + 0.5f) * slot, 0.002f);
            tabs.add(tab);
            addChild(tab);
        }

        if (!panels.isEmpty()) {
            select(0);
        }
    }

    /** Swaps in page {@code index}, notifying the old/new pages. */
    private void select(int index) {
        if (index == current || index < 0 || index >= panels.size()) {
            return;
        }
        if (current >= 0) {
            panels.get(current).onHide();
            pageHost.removeChild(pages.get(current));
            tabs.get(current).setLit(false);
        }
        current = index;
        pageHost.addChild(pages.get(index));
        tabs.get(index).setLit(true);
        if (live) {
            panels.get(index).onShow();
        }
    }

    /** The show/hide poll gating only runs while the window is up. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        updateGating();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        updateGating();
    }

    private void updateGating() {
        boolean now = isEnabled() && isVisible();
        if (now == live || current < 0) {
            return;
        }
        live = now;
        if (live) {
            panels.get(current).onShow();
        } else {
            panels.get(current).onHide();
        }
    }
}
