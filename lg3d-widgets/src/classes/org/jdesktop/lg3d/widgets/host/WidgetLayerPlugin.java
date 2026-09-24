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
package org.jdesktop.lg3d.widgets.host;

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
 * Scene-manager plugin that puts the desktop widget layer on the scene.
 *
 * <p>Registered from {@code glassy.lgcfg}; {@link #getPluginRoot()} returns the
 * {@link WidgetLayer}, which the scene manager adds to the scene root. On
 * initialization it builds a {@link WidgetHost} and loads the persisted widget
 * layout (seeding a clock and a temperature widget on a fresh desktop). It also
 * listens for {@link ScreenResolutionChangedEvent} to re-place widgets when the
 * screen size changes.</p>
 *
 * <p>The plugin is a singleton in the running desktop, so it exposes the live host
 * through {@link #host()} for the widget gallery app to add/remove widgets.</p>
 */
public class WidgetLayerPlugin implements SceneManagerPlugin {
    private static final Logger logger = Logger.getLogger("lg.widgets");

    /** The live host, for apps (e.g. the gallery) running in the same JVM. */
    private static volatile WidgetHost currentHost;

    private WidgetLayer layer;
    private WidgetHost host;
    private LgEventListener resolutionListener;

    public WidgetLayerPlugin() {
    }

    /**
     * The active widget host, or null if the widget layer is not running. The
     * gallery app uses this to add/remove widgets on the live desktop.
     */
    public static WidgetHost host() {
        return currentHost;
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        layer = new WidgetLayer();
        host = new WidgetHost(layer);
        currentHost = host;

        resolutionListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                try {
                    host.relayout();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "widget relayout failed", t);
                }
            }
            @Override
            public Class<LgEvent>[] getTargetEventClasses() {
                return new Class[]{ScreenResolutionChangedEvent.class};
            }
        };
        LgEventConnector.getLgEventConnector().addListener(
                LgEventSource.ALL_SOURCES, resolutionListener);

        try {
            host.loadPersisted();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not load persisted widgets", t);
        }
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
        WidgetHost h = host;
        if (h != null) {
            h.dispose();
            host = null;
        }
        if (currentHost == h) {
            currentHost = null;
        }
        layer = null;
    }

    @Override
    public boolean isRemovable() {
        return true;
    }
}
