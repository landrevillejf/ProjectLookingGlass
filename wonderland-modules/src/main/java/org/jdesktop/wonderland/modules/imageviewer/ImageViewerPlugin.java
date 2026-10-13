/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (imageviewer): the module's WonderlandPlugin provider.
 *
 * The headless half of the image-viewer feature module. Its Java 3D renderer
 * (an image cell painted from the ImageModel) is deferred to M6.
 */
package org.jdesktop.wonderland.modules.imageviewer;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The image-viewer feature module. */
public final class ImageViewerPlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "imageviewer";

    private final ModuleInfo info = new ModuleInfo(
            MODULE_NAME, 1, 0, 0, "View still images on a cell");

    private ImageModel model;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        model = new ImageModel();
    }

    @Override
    public void dispose() {
        model = null;
    }

    /** The live image model, or {@code null} before initialize()/after dispose(). */
    public ImageModel getModel() {
        return model;
    }
}
