/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c/M6c2: the M4-ServiceLoader plugin entry point.
 *
 * When the WonderlandPluginLoader discovers this provider it calls
 * initialize(PluginContext), which installs the live renderer into the
 * AvatarRendererInstallers slot. The world's own scene graph is built later, on
 * demand, by createDesktop() -- that second step needs a running lg3d session
 * and is therefore probe-only, while this first step is a lambda and is safe to
 * assert headless.
 *
 * The split is deliberate: the renderer seam can be registered everywhere (a
 * cell loaded without it would render nothing), but a Component3D cannot exist
 * in a JVM that has no desktop to put it in.
 */
package org.jdesktop.wonderland.desktop;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.jdesktop.wonderland.renderer.bootstrap.WonderlandRendererBootstrap;

/**
 * The {@link WonderlandPlugin} provider for the wonderland-desktop module.
 * Discovered via {@code META-INF/services}.
 */
public final class WonderlandDesktopPlugin implements WonderlandPlugin {

    private static final ModuleInfo MODULE_INFO = new ModuleInfo(
            "wonderland-desktop", 1, 0, 0,
            "Bridges wonderland cells into the lg3d desktop scene graph");

    private WonderlandDesktop desktop;
    private boolean initialized;

    @Override
    public ModuleInfo getModuleInfo() {
        return MODULE_INFO;
    }

    @Override
    public void initialize(PluginContext context) {
        if (initialized) {
            return;
        }
        // Installing the renderer registers a factory; nothing Java 3D is
        // constructed until a cell actually loads, so this half of the boot
        // sequence runs headless too.
        WonderlandRendererBootstrap.install();
        initialized = true;
    }

    /**
     * M6c2: build the live world and put it on the desktop -- the container, the
     * renderer install, the Frame3D the scene manager owns, and the pick routing
     * that connects the desktop's mouse events to the cells.
     *
     * <p>Probe-only: the first line needs a running lg3d session. Idempotent, so
     * a launcher and a plugin can both ask.</p>
     */
    public WonderlandDesktop createDesktop() {
        if (!initialized || desktop != null) {
            return desktop;
        }
        WonderlandDesktop live = new WonderlandDesktop();
        live.installRenderer();
        live.show();
        live.installInputRouting(null);
        desktop = live;
        return desktop;
    }

    /** The live desktop, or null before {@link #createDesktop()}. */
    public WonderlandDesktop getDesktop() {
        return desktop;
    }

    @Override
    public void dispose() {
        if (!initialized) {
            return;
        }
        initialized = false;
        if (desktop != null) {
            desktop.getBridge().unmountAll();
            desktop = null;
        }
        // Put back the pre-M6b slot so a reload of the modules does not leave a
        // renderer whose scene graphs no longer exist.
        WonderlandRendererBootstrap.uninstall();
    }

    /**
     * Whether this plugin has been initialised. Exposed for the test and the
     * desktop's lifecycle coordination.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
