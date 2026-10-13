/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (whiteboard): the module's WonderlandPlugin provider.
 *
 * The headless half of the shared drawing surface. Its stroke renderer is
 * deferred to M6.
 */
package org.jdesktop.wonderland.modules.whiteboard;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The whiteboard feature module. */
public final class WhiteboardPlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "whiteboard";

    private final ModuleInfo info = new ModuleInfo(
            MODULE_NAME, 1, 0, 0, "A shared freehand drawing surface");

    private WhiteboardModel model;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        model = new WhiteboardModel();
    }

    @Override
    public void dispose() {
        model = null;
    }

    /** The live surface, or {@code null} before initialize()/after dispose(). */
    public WhiteboardModel getModel() {
        return model;
    }
}
