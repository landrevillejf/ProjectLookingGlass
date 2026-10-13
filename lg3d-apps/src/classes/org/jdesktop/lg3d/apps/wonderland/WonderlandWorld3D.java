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
package org.jdesktop.lg3d.apps.wonderland;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.wonderland.desktop.CellFrameBridge;
import org.jdesktop.wonderland.desktop.WonderlandDesktop;
import org.jdesktop.wonderland.desktop.WorldHudModel;

/**
 * The 3D half of the Wonderland desktop integration: the start-menu command
 * that puts a live wonderland world into the virtual universe and opens the
 * {@link WonderlandHudPanel} beside it.
 *
 * <p>This class is the reason {@code wonderland-desktop} exists but was not
 * reachable: the bridge, the pick routing and the HUD model were all built and
 * tested there, and nothing in the running desktop ever asked for them. A
 * {@code .lgcfg} descriptor naming this class fixes that, because a
 * {@code java <class>} start-menu command runs its {@code main} <em>in the
 * desktop's own JVM</em> (see {@code AppLaunchAction}) &mdash; which is exactly
 * what a {@code Frame3D} needs: the live {@code AppConnector} session and the
 * scene manager that owns the world's window.</p>
 *
 * <p>The sequence is the one {@code WonderlandDesktop}'s own javadoc describes:
 * install the renderer before any cell loads, hand the world to the scene
 * manager as a frame, then connect the desktop's picked events to the cells.
 * The HUD is hosted through {@link TitledSwingWindow}, the same SwingNode bridge
 * every other 2D panel in this package uses, so the world's control surface
 * behaves like a desktop window rather than a floating texture.</p>
 *
 * <p>Probe-only: constructing this class needs the running 3D desktop, so CI
 * never instantiates it. In the 2D/Swing desktop the command is not offered at
 * all &mdash; {@code Desktop2DAppRegistry} classifies it
 * {@code UNAVAILABLE}, which drops it from that menu instead of opening a window
 * whose scene graph has nowhere to go. The read-only inventory of the world
 * remains available there through {@link WonderlandWorld}.</p>
 *
 * <p><strong>What it does not do yet.</strong> The frame opens and the HUD lists
 * the server's cells, but nothing calls {@code cellLoaded} until the client owns
 * a real session: the port has <em>no</em> production {@code WonderlandSession}
 * implementation (M2e/M3 tested everything above that seam against an in-JVM
 * loopback, and {@code SessionFeed} was built for the same reason), so every HUD
 * row is reported {@code PENDING} and its Show / Hide stays disabled rather than
 * pretending to toggle a cell that was never mounted. That is the named remaining
 * slice of the Wonderland port &mdash; a {@code java.net.http.WebSocket}
 * transport onto the M3b {@code /comms} endpoint &mdash; not something this
 * launcher can fake from the desktop side.</p>
 */
public final class WonderlandWorld3D {

    /** The title bar text of the HUD window. */
    static final String HUD_TITLE = "Wonderland World HUD";

    /**
     * Start-menu entry point. Any argument is ignored: the server to read is
     * {@link WorldCatalog#SERVER_PROPERTY}, so the descriptor stays a plain
     * {@code java <class>} command.
     */
    public static void main(String[] args) {
        new WonderlandWorld3D();
    }

    /**
     * Builds the live world and its HUD.
     *
     * @throws RuntimeException when no 3D desktop session is running; the
     *         start-menu launcher logs it and leaves the desktop otherwise intact
     */
    WonderlandWorld3D() {
        TitledSwingWindow.installHostedLookAndFeel();

        WonderlandDesktop desktop = new WonderlandDesktop();
        // Order matters: the renderer installer has to be in place before the
        // first cell loads, or a loaded cell would render nothing.
        desktop.installRenderer();
        desktop.show();
        // No sink yet: the decisions are logged nowhere and every cell still
        // gets its gesture, because the router owns the rule, not this caller.
        desktop.installInputRouting(null);

        CellFrameBridge bridge = desktop.getBridge();
        WorldHudModel model = new WorldHudModel(bridge);
        String server = WorldCatalog.defaultServer();
        WonderlandHudPanel hud = new WonderlandHudPanel(model, bridge,
                () -> WorldCatalogInventory.read(server));
        // Closing the HUD leaves the world empty rather than orphaned: the cells
        // go back out of the scene graph, and the (now blank) world window is
        // dismissed like any other desktop window.
        hud.setOnClose(bridge::unmountAll);

        TitledSwingWindow.show(HUD_TITLE, hud,
                WonderlandHudPanel.WIDTH_PX, WonderlandHudPanel.HEIGHT_PX);
    }
}
