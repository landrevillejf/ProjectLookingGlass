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
package org.jdesktop.lg3d.scenemanager.utils.hud;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.event.ScreenResolutionChangedEvent;
import org.jdesktop.lg3d.scenemanager.utils.plugin.SceneManagerPlugin;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.LgEventSource;

/**
 * Scene-manager plugin that puts the front-most desktop HUD layer on the scene.
 *
 * <p>Registered from {@code glassy.lgcfg}; {@link #getPluginRoot()} returns the
 * {@link DesktopHudLayer}, which the scene manager adds to the scene root. The
 * layer floats in front of every application window, so it is the non-taskbar
 * host for the transient chrome ported from the 2D desktop: notification toasts,
 * the Alt+Tab window switcher, the run dialog, the desktop context menu, the snap
 * preview, the workspace pager and the brightness dim.</p>
 *
 * <p>The plugin is a singleton in the running desktop, so it exposes the live
 * layer through {@link #layer()} for sibling plugins and apps (running in the same
 * JVM) to attach their overlays. It listens for
 * {@link ScreenResolutionChangedEvent} to re-apply the front pose and refresh the
 * cached screen size when the display changes.</p>
 */
public class DesktopHudPlugin implements SceneManagerPlugin {
    private static final Logger logger = Logger.getLogger("lg.hud");

    /** The live HUD layer, for plugins/apps running in the same JVM. */
    private static volatile DesktopHudLayer currentLayer;

    private DesktopHudLayer layer;
    private LgEventListener resolutionListener;

    public DesktopHudPlugin() {
    }

    /**
     * The active HUD layer, or null if the HUD plugin is not running. Overlay
     * contributors (toasts, switcher, run dialog, ...) use this to attach nodes.
     */
    public static DesktopHudLayer layer() {
        return currentLayer;
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        layer = new DesktopHudLayer();
        currentLayer = layer;

        resolutionListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                try {
                    layer.updateScreenSize();
                    layer.applyFrontPose();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "HUD relayout failed", t);
                }
            }
            @Override
            public Class<LgEvent>[] getTargetEventClasses() {
                return new Class[]{ScreenResolutionChangedEvent.class};
            }
        };
        LgEventConnector.getLgEventConnector().addListener(
                LgEventSource.ALL_SOURCES, resolutionListener);
    }

    @Override
    public Component3D getPluginRoot() {
        return layer;
    }

    @Override
    public void destroy() {
        if (resolutionListener != null) {
            LgEventConnector.getLgEventConnector().removeListener(
                    LgEventSource.ALL_SOURCES, resolutionListener);
            resolutionListener = null;
        }
        DesktopHudLayer l = layer;
        if (currentLayer == l) {
            currentLayer = null;
        }
        layer = null;
    }

    @Override
    public boolean isRemovable() {
        return true;
    }
}
