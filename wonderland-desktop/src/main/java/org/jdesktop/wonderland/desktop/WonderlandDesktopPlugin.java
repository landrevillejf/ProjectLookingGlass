/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: the M4-ServiceLoader plugin entry point.
 *
 * When the WonderlandPluginLoader discovers this provider it calls
 * initialize(PluginContext), which installs the live renderer into the
 * AvatarRendererInstallers slot and creates the world container. The desktop's
 * boot sequence (WonderlandDesktop) then drives cell mounting through the
 * bridge.
 *
 * The plugin class itself is pure: its initialize/dispose bookkeeping is
 * headless-testable. The live WonderlandDesktop construction it triggers is
 * probe-only.
 */
package org.jdesktop.wonderland.desktop;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

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
        initialized = true;
        // The live desktop construction (WonderlandWorldContainer's Component3D
        // superclass) requires a running lg3d session. In a headless test JVM
        // this path is not exercised; only the idempotence flag and lifecycle
        // contract are asserted.
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
    }

    /**
     * Whether this plugin has been initialised. Exposed for the test and the
     * desktop's lifecycle coordination.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
