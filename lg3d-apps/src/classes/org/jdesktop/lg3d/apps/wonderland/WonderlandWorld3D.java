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

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.ConnectionFailureException;
import org.jdesktop.wonderland.client.comms.WebSocketWonderlandSession;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.desktop.CellFrameBridge;
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
 * <p><strong>The live session.</strong> The frame opens, then one daemon
 * thread first <em>ensures the server</em> and walks in over the sockets:
 * a {@link WebSocketWonderlandSession}
 * (three {@code /comms} connections converged by the server onto one session)
 * driven by a {@link CellClientSession}, exactly the fan-out the M3b loopback
 * proved in-JVM. The ensure step is the same guarantee the 2D browser panel
 * gives through {@link WonderlandServerLauncher} &mdash; a local address whose
 * live world is not confirmed (nothing answers, or a server that answers
 * without serving a seeded world) gets a server the desktop starts, and a
 * relocated spawn is followed &mdash; because Wonderland must execute
 * identically whichever desktop launched it: the walk-in may not silently
 * trust an empty server the panel would have replaced. The cache listener is
 * registered <em>before</em> connecting,
 * because the server's view-cell flush starts with the SET_VIEW handshake
 * itself &mdash; a listener added after {@code connect} would miss the cells
 * that arrive during it. Each {@code cellLoaded} mounts its renderer into the
 * world through {@link WonderlandDesktop#cellLoaded}; each {@code cellUnloaded}
 * takes it back out. A server that refuses the walk-in leaves the HUD and the
 * empty world intact and says so on the console: the world is what failed to
 * connect, not the desktop.</p>
 *
 * <p>The HUD's inventory reads the server's <em>live</em> world
 * ({@code /api/world/cells}, the same {@link WorldCatalogInventory} the panel
 * uses), whose cell ids are the real {@link CellID}s the mounts are keyed by,
 * so a seeded world's rows join the bridge's mounted set instead of sitting
 * {@code PENDING} beside it.</p>
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
     * Builds the live world and its HUD, then walks a real client session in
     * over the server's {@code /comms} sockets on a daemon thread.
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
        // The address the world actually lives at once the server is ensured;
        // the HUD's inventory follows a relocation the same way the session
        // does, so both surfaces read the server that really hosts the cells.
        AtomicReference<String> live = new AtomicReference<>(WorldCatalog.defaultServer());
        WonderlandHudPanel hud = new WonderlandHudPanel(model, bridge,
                () -> WorldCatalogInventory.read(live.get()));

        // The live walk-in. The listener goes on the cache before connect()
        // runs, so the cells the SET_VIEW handshake flushes have somewhere to
        // land; every mount/unmount is then the bridge's, on the scene thread.
        CellClientSession client = new CellClientSession();
        client.getCellCache().addCellCacheListener(new CellCache.CellCacheListener() {
            @Override
            public void cellLoaded(CellID cellId, Cell cell) {
                desktop.cellLoaded(cell);
            }

            @Override
            public void cellLoadFailed(CellID cellId, String className,
                    CellID parentCellID, Throwable cause) {
                // The row simply stays PENDING in the HUD: the cell never made
                // it into the scene graph, and an honest pending row beats a
                // stack trace on the desktop's console.
            }

            @Override
            public void cellUnloaded(CellID cellId, Cell cell) {
                desktop.cellUnloaded(cell);
            }
        });
        connectSession(new WonderlandServerLauncher(), live, client, hud);

        // Closing the HUD leaves the world empty rather than orphaned: the
        // session's sockets go first, so no fan-out writes into a dead bridge,
        // then the cells come out of the scene graph and the (now blank) world
        // window is dismissed like any other desktop window.
        hud.setOnClose(() -> {
            client.disconnect();
            bridge.unmountAll();
        });

        TitledSwingWindow.show(HUD_TITLE, hud,
                WonderlandHudPanel.WIDTH_PX, WonderlandHudPanel.HEIGHT_PX);
    }

    /**
     * Ensure the server, then connect it off the calling thread &mdash; the
     * spawn wait and the handshake both block and must never stall the HUD's
     * paint. The HUD's inventory reads the ensured address, so a relocated
     * spawn moves the whole surface, not just the sockets. On failure the
     * world stays empty and the reason is printed, naming the command that
     * brings a server up. Never throws.
     */
    private static void connectSession(WonderlandServerLauncher launcher,
            AtomicReference<String> live, CellClientSession client,
            WonderlandHudPanel hud) {
        Thread walker = new Thread(() -> {
            String server = liveServer(launcher, live.get());
            live.set(server);
            try {
                client.connect(WebSocketWonderlandSession.forServer(
                        server, identity()));
            } catch (ConnectionFailureException | RuntimeException failed) {
                System.err.println("WonderlandWorld3D: no session to "
                        + server + " (" + failed.getMessage() + "); "
                        + "start one: " + WorldCatalog.startCommand());
            }
            SwingUtilities.invokeLater(hud::refresh);
        }, "WonderlandWorld3D-session");
        walker.setDaemon(true);
        walker.start();
    }

    /**
     * The address to walk in to, ensured exactly the way the 2D browser panel
     * ensures it: an address that already <em>serves</em> a world is kept as
     * it is, a local one that does not gets a server this desktop starts, and
     * a relocated spawn is followed to its new port. Wonderland runs on one
     * contract whichever desktop launched it &mdash; the 3D walk-in may not
     * trust a server the panel would have replaced (an answering process from
     * an older jar with zero cells is precisely that). A remote address is
     * not ours to start, and a local one that cannot be brought up is still
     * tried, so the session's own failure message stays the last word. Pure
     * against the injected launcher, so the rule is a tested claim.
     */
    static String liveServer(WonderlandServerLauncher launcher, String asked) {
        WonderlandServerLauncher.Result started = launcher.ensureRunning(asked, null);
        if (started.ready()) {
            String serving = started.serverUrl(asked);
            if (!serving.equals(asked)) {
                System.err.println("WonderlandWorld3D: " + started.note());
            }
            return serving;
        }
        System.err.println("WonderlandWorld3D: no wonderland-server confirmed "
                + "for " + asked + " (" + started.note() + "); connecting anyway");
        return asked;
    }

    /** The identity the attach carries: the desktop's own user, no prompt. */
    private static Properties identity() {
        String user = System.getProperty("user.name", "guest");
        Properties props = new Properties();
        props.setProperty("username", user);
        props.setProperty("fullname", user);
        return props;
    }
}
