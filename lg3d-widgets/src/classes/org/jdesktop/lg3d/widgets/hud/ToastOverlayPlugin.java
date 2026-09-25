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
package org.jdesktop.lg3d.widgets.hud;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudLayer;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudPlugin;
import org.jdesktop.lg3d.scenemanager.utils.hud.NotificationService;
import org.jdesktop.lg3d.scenemanager.utils.plugin.SceneManagerPlugin;
import org.jdesktop.lg3d.wg.Component3D;

/**
 * Scene-manager plugin that mounts the notification toast stack on the front-most
 * desktop HUD layer. It is the native 3D counterpart of the 2D/Swing desktop's
 * toast layer, and the first end-to-end consumer of {@link DesktopHudPlugin}.
 *
 * <p>Registered from {@code glassy.lgcfg} <em>after</em> {@link DesktopHudPlugin},
 * so that {@link DesktopHudPlugin#layer()} is non-null by the time this plugin
 * initializes. It builds a {@link ToastOverlay3D} over
 * {@link NotificationService#get()}, attaches it to the HUD layer at the screen
 * centre (individual cards position themselves in the bottom-right) and starts its
 * refresh timer.</p>
 *
 * <p>{@link #getPluginRoot()} returns null: the overlay is parented to the HUD
 * layer rather than the scene root, so it inherits the layer's perspective-
 * compensated front pose and floats above every application window.</p>
 */
public class ToastOverlayPlugin implements SceneManagerPlugin {
    private static final Logger logger = Logger.getLogger("lg.hud.toast");

    private ToastOverlay3D overlay;
    private DesktopHudLayer layer;
    private int stackDepth = -1;

    public ToastOverlayPlugin() {
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        layer = DesktopHudPlugin.layer();
        if (layer == null) {
            logger.warning("DesktopHudPlugin is not running; toast overlay disabled. "
                    + "Register ToastOverlayPlugin after DesktopHudPlugin in glassy.lgcfg.");
            return;
        }
        try {
            overlay = new ToastOverlay3D(NotificationService.get(), layer);
            layer.addChild(overlay);
            // The overlay sits at the screen centre; its cards position themselves.
            stackDepth = layer.placeAtFront(overlay, 0.5f, 0.5f);
            overlay.start();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not start the toast overlay", t);
        }
    }

    @Override
    public Component3D getPluginRoot() {
        // Attached to the HUD layer, not the scene root.
        return null;
    }

    @Override
    public void destroy() {
        ToastOverlay3D o = overlay;
        if (o != null) {
            try {
                o.dispose();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "toast overlay dispose failed", t);
            }
            if (layer != null) {
                layer.removeChild(o);
                layer.releaseStackSlot();
            }
            overlay = null;
        }
        stackDepth = -1;
        layer = null;
    }

    @Override
    public boolean isRemovable() {
        return true;
    }
}
