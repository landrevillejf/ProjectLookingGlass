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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.utils.action.ActionBoolean;
import org.jdesktop.lg3d.utils.eventaction.Component3DMover;
import org.jdesktop.lg3d.utils.eventadapter.Component3DManualMoveEventAdapter;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.widgets.api.AbstractWidget;
import org.jdesktop.lg3d.widgets.api.Widget;
import org.jdesktop.lg3d.widgets.api.WidgetConfigStore;
import org.jdesktop.lg3d.widgets.api.WidgetContext;
import org.jdesktop.lg3d.widgets.api.WidgetRegistry;
import org.jogamp.vecmath.Vector3f;

/**
 * Owns the set of widget instances on the desktop: creates them from the
 * {@link WidgetRegistry}, places them on the {@link WidgetLayer}, makes them
 * draggable, and persists the layout (which instances, their types and positions)
 * through a {@link WidgetConfigStore}.
 *
 * <p>One shared {@link ScheduledExecutorService} drives every widget's periodic
 * updates, so adding widgets does not add threads.</p>
 */
public class WidgetHost {
    private static final Logger logger = Logger.getLogger("lg.widgets");

    /** Default widgets seeded on a fresh desktop (no saved config). */
    private static final String[][] DEFAULTS = {
        // { typeId, fx, fy }
        {"clock", "0.10", "0.14"},
        {"temperature", "0.10", "0.40"},
    };

    private final WidgetLayer layer;
    private final WidgetConfigStore config;
    private final WidgetRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final WidgetContext context;

    /** instanceId -> widget, in placement order. */
    private final Map<String, Widget> instances = new LinkedHashMap<>();
    /** instanceId -> current fractional position {fx, fy}. */
    private final Map<String, float[]> positions = new LinkedHashMap<>();

    private int idCounter = 0;

    public WidgetHost(WidgetLayer layer) {
        this.layer = layer;
        this.config = new WidgetConfigStore();
        this.registry = WidgetRegistry.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private int n = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "lg3d-widget-" + (n++));
                t.setDaemon(true);
                return t;
            }
        });
        this.context = new WidgetContext(scheduler, config);
    }

    public WidgetLayer layer() { return layer; }
    public WidgetConfigStore config() { return config; }
    public WidgetContext context() { return context; }

    /** The instance ids currently on the desktop, in placement order. */
    public List<String> instanceIds() {
        return new ArrayList<>(instances.keySet());
    }

    public Widget getWidget(String instanceId) {
        return instances.get(instanceId);
    }

    /** The type id of a placed instance, or null. */
    public String typeOf(String instanceId) {
        return config.getType(instanceId);
    }

    /**
     * Loads the persisted layout, or seeds the defaults (clock + temperature) on a
     * fresh desktop.
     */
    public void loadPersisted() {
        List<String> ids = config.instances();
        if (ids.isEmpty()) {
            for (String[] def : DEFAULTS) {
                addWidget(def[0], Float.parseFloat(def[1]), Float.parseFloat(def[2]));
            }
            return;
        }
        for (String id : ids) {
            String type = config.getType(id);
            if (type == null || !registry.contains(type)) {
                logger.log(Level.WARNING, "Skipping unknown persisted widget: {0}", id);
                continue;
            }
            float fx = config.getX(id, 0.1f);
            float fy = config.getY(id, 0.1f);
            placeInstance(id, type, fx, fy);
        }
    }

    /**
     * Creates and places a new widget instance of the given type, persists it, and
     * returns its instance id (or null if the type is unknown).
     */
    public String addWidget(String typeId, float fx, float fy) {
        if (!registry.contains(typeId)) {
            logger.log(Level.WARNING, "Cannot add unknown widget type: {0}", typeId);
            return null;
        }
        String instanceId = generateInstanceId(typeId);
        config.addInstance(instanceId);
        config.setType(instanceId, typeId);
        config.setPosition(instanceId, fx, fy);
        config.save();
        placeInstance(instanceId, typeId, fx, fy);
        return instanceId;
    }

    /** Adds a widget at the first free spot in a simple top-left cascade. */
    public String addWidgetAtFreeSpot(String typeId) {
        float[] spot = nextFreeSpot();
        return addWidget(typeId, spot[0], spot[1]);
    }

    /** Removes and disposes a widget instance, and forgets it from the config. */
    public void removeWidget(String instanceId) {
        Widget w = instances.remove(instanceId);
        positions.remove(instanceId);
        if (w != null) {
            try {
                layer.removeChild(w.node());
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "removeChild failed", e);
            }
            w.stop();
            w.dispose();
        }
        config.removeInstance(instanceId);
        config.save();
    }

    /** Re-applies persisted fractional positions (e.g. after a resolution change). */
    public void relayout() {
        layer.updateScreenSize();
        for (Map.Entry<String, Widget> e : instances.entrySet()) {
            float[] pos = positions.get(e.getKey());
            if (pos != null) {
                layer.placeAt(e.getValue().node(), pos[0], pos[1]);
            }
        }
    }

    private void placeInstance(String instanceId, String typeId, float fx, float fy) {
        Widget widget = registry.create(typeId);
        if (widget == null) {
            return;
        }
        if (widget instanceof AbstractWidget) {
            ((AbstractWidget) widget).setInstanceId(instanceId);
        }
        try {
            widget.init(context);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Widget init failed: " + typeId, t);
            return;
        }
        Component3D node = widget.node();
        layer.placeAt(node, fx, fy);
        installDrag(instanceId, node);
        layer.addChild(node);
        instances.put(instanceId, widget);
        positions.put(instanceId, new float[]{fx, fy});
        try {
            widget.start();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Widget start failed: " + typeId, t);
        }
    }

    /**
     * Makes a widget node draggable in the screen plane using the framework's own
     * {@link Component3DMover}; when the drag ends the position is clamped on
     * screen and persisted.
     */
    private void installDrag(final String instanceId, final Component3D node) {
        node.addListener(new Component3DMover(false));
        node.addListener(new Component3DManualMoveEventAdapter(new ActionBoolean() {
            @Override
            public void performAction(LgEventSource source, boolean started) {
                if (started) {
                    return;
                }
                layer.clampToScreen(node);
                Vector3f t = node.getTranslation(new Vector3f());
                float fx = layer.worldXToFraction(t.x);
                float fy = layer.worldYToFraction(t.y);
                positions.put(instanceId, new float[]{fx, fy});
                config.setPosition(instanceId, fx, fy);
                config.save();
            }
        }));
    }

    private String generateInstanceId(String typeId) {
        String base = typeId + "-" + Long.toString(System.currentTimeMillis(), 36);
        String id;
        do {
            id = base + "-" + (idCounter++);
        } while (instances.containsKey(id) || config.instances().contains(id));
        return id;
    }

    /**
     * Picks a free spot for a new widget: cascades down the left edge, wrapping
     * back to the top when it runs out of room.
     */
    private float[] nextFreeSpot() {
        int n = instances.size();
        float fx = 0.10f + (n % 4) * 0.20f;
        float fy = 0.14f + (n / 4) * 0.22f;
        if (fy > 0.80f) {
            fy = 0.14f;
        }
        if (fx > 0.80f) {
            fx = 0.10f;
        }
        return new float[]{fx, fy};
    }

    /** Stops all widgets and shuts down the scheduler. */
    public void dispose() {
        for (Widget w : instances.values()) {
            try {
                w.stop();
                w.dispose();
            } catch (Throwable t) {
                logger.log(Level.FINE, "widget dispose failed", t);
            }
        }
        instances.clear();
        positions.clear();
        scheduler.shutdownNow();
    }
}
