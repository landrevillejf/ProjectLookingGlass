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
package org.jdesktop.lg3d.widgets.swing;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JDesktopPane;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.widgets.api.WidgetConfigStore;
import org.jdesktop.lg3d.widgets.builtin.BuiltinWidgetCards;
import org.jdesktop.lg3d.widgets.builtin.WidgetCard;
import org.jdesktop.lg3d.widgets.builtin.WidgetCardSpec;

/**
 * The conventional-Swing desktop's widget layer: the 2D counterpart of the 3D
 * {@code WidgetHost}/{@code WidgetLayer}. It drops the same pure-Swing
 * {@link WidgetCard}s onto the desktop's {@link JDesktopPane} - behind the
 * application internal frames, above the wallpaper - makes them draggable, and
 * persists the layout through the very same {@link WidgetConfigStore}
 * ({@code ~/.config/lg3d/widgets.properties}) the 3D desktop uses, so a layout
 * saved under one desktop is honoured by the other.
 *
 * <p>Nothing here references Java 3D, so the layer installs and runs on a JVM
 * where the Java 3D jars are missing entirely. {@code Desktop2D} installs it
 * reflectively via {@link #install(JDesktopPane)}; the widget gallery reaches the
 * live layer through {@link #current()} to add and remove widgets.</p>
 *
 * <p>One shared {@link ScheduledExecutorService} drives every card's periodic
 * {@link WidgetCard#tick()}; a tick updates {@code volatile} model fields and
 * calls {@code repaint()}, so adding widgets adds no threads and no EDT work.</p>
 */
public final class SwingWidgetLayer {
    private static final Logger logger = Logger.getLogger("lg.widgets");

    /** Default widgets seeded on a fresh desktop (no saved config). Matches the
     *  3D {@code WidgetHost} defaults so both desktops start the same way. */
    private static final String[][] DEFAULTS = {
        // { typeId, fx, fy }
        {"clock", "0.10", "0.14"},
        {"temperature", "0.10", "0.40"},
    };

    /**
     * Layer the cards live on: below the internal frames (DEFAULT_LAYER, 0) so
     * application windows cover them, but above the wallpaper (painted by the
     * desktop pane itself).
     */
    private static final int CARD_LAYER = JLayeredPane.FRAME_CONTENT_LAYER;

    /** Pointer travel, in pixels, that turns a press into a drag (not a click). */
    private static final int DRAG_THRESHOLD = 4;

    /** The live layer, for apps (e.g. the gallery) running in the same JVM. */
    private static volatile SwingWidgetLayer current;

    private final JDesktopPane desktop;
    private final WidgetConfigStore config;
    private final ScheduledExecutorService scheduler;

    /** instanceId -> card, in placement order. */
    private final Map<String, WidgetCard> cards = new LinkedHashMap<>();
    /** instanceId -> current fractional position {fx, fy}. */
    private final Map<String, float[]> positions = new LinkedHashMap<>();
    /** instanceId -> its periodic tick, if any. */
    private final Map<String, ScheduledFuture<?>> ticks = new LinkedHashMap<>();

    private final ComponentAdapter resizeListener;
    private int idCounter = 0;

    /**
     * Installs a widget layer on {@code desktop}, loads the persisted layout
     * (seeding the defaults on a fresh desktop) and publishes it as
     * {@link #current()}. Called reflectively by the 2D desktop shell.
     */
    public static void install(JDesktopPane desktop) {
        if (desktop == null) {
            return;
        }
        SwingWidgetLayer layer = new SwingWidgetLayer(desktop, new WidgetConfigStore());
        current = layer;
        try {
            layer.loadPersisted();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not load persisted widgets", t);
        }
        logger.log(Level.INFO, "2D widget layer installed with {0} widget(s)",
                layer.cards.size());
    }

    /** The active 2D widget layer, or null if it is not installed. */
    public static SwingWidgetLayer current() {
        return current;
    }

    /** Detaches and disposes the live layer (used on desktop shutdown). */
    public static void uninstall() {
        SwingWidgetLayer layer = current;
        current = null;
        if (layer != null) {
            layer.dispose();
        }
    }

    public SwingWidgetLayer(JDesktopPane desktop, WidgetConfigStore config) {
        this.desktop = desktop;
        this.config = (config != null) ? config : new WidgetConfigStore();
        this.scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private int n = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "lg3d-widget2d-" + (n++));
                t.setDaemon(true);
                return t;
            }
        });
        this.resizeListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                relayout();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                relayout();
            }
        };
        this.desktop.addComponentListener(resizeListener);
    }

    public WidgetConfigStore config() {
        return config;
    }

    /** The instance ids currently on the desktop, in placement order. */
    public List<String> instanceIds() {
        return new ArrayList<>(cards.keySet());
    }

    /** The type id of a placed instance, or null. */
    public String typeOf(String instanceId) {
        return config.getType(instanceId);
    }

    /**
     * Loads the persisted layout, or seeds the defaults (clock + temperature) on
     * a fresh desktop.
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
            if (type == null || !BuiltinWidgetCards.contains(type)) {
                logger.log(Level.WARNING, "Skipping unknown persisted widget: {0}", id);
                continue;
            }
            float fx = config.getX(id, 0.1f);
            float fy = config.getY(id, 0.1f);
            placeInstance(id, type, fx, fy);
        }
    }

    /**
     * Creates and places a new widget of the given type, persists it and returns
     * its instance id (or null if the type is unknown).
     */
    public String addWidget(String typeId, float fx, float fy) {
        if (!BuiltinWidgetCards.contains(typeId)) {
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

    /** Removes a widget instance, stops its tick and forgets it from the config. */
    public void removeWidget(String instanceId) {
        WidgetCard card = cards.remove(instanceId);
        positions.remove(instanceId);
        ScheduledFuture<?> tick = ticks.remove(instanceId);
        if (tick != null) {
            tick.cancel(false);
        }
        if (card != null) {
            try {
                card.stop();
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "card stop failed", e);
            }
            desktop.remove(card);
        }
        config.removeInstance(instanceId);
        config.save();
        desktop.revalidate();
        desktop.repaint();
    }

    /** Re-applies persisted fractional positions (e.g. after a resize). */
    public void relayout() {
        for (Map.Entry<String, WidgetCard> e : cards.entrySet()) {
            float[] pos = positions.get(e.getKey());
            if (pos != null) {
                applyBounds(e.getValue(), pos[0], pos[1]);
            }
        }
        desktop.revalidate();
        desktop.repaint();
    }

    private void placeInstance(String instanceId, String typeId, float fx, float fy) {
        WidgetCardSpec spec = BuiltinWidgetCards.forId(typeId);
        if (spec == null) {
            return;
        }
        WidgetCard card;
        try {
            card = spec.create();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Widget card creation failed: " + typeId, t);
            return;
        }
        card.attach(config, instanceId, scheduler);
        applyBounds(card, fx, fy);
        installInteraction(instanceId, card);
        desktop.add(card);
        desktop.setLayer(card, CARD_LAYER);
        cards.put(instanceId, card);
        positions.put(instanceId, new float[]{fx, fy});
        try {
            card.start();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Widget card start failed: " + typeId, t);
        }
        scheduleTick(instanceId, card);
        desktop.revalidate();
        desktop.repaint();
    }

    private void scheduleTick(String instanceId, WidgetCard card) {
        long period = card.tickPeriodMillis();
        if (period <= 0) {
            return;
        }
        Runnable safe = () -> {
            try {
                card.tick();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Widget tick failed: " + card.id(), t);
            }
        };
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                safe, 0L, period, TimeUnit.MILLISECONDS);
        ticks.put(instanceId, future);
    }

    /**
     * Wires drag-to-move plus the card's click / wheel interaction. A press that
     * travels more than {@link #DRAG_THRESHOLD} pixels is a drag (and suppresses
     * the click); on release the card is clamped on screen and its new fractional
     * position persisted.
     */
    private void installInteraction(final String instanceId, final WidgetCard card) {
        MouseAdapter handler = new MouseAdapter() {
            private Point press;
            private Point origin;
            private boolean dragged;

            @Override
            public void mousePressed(MouseEvent e) {
                press = e.getPoint();
                origin = card.getLocation();
                dragged = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (press == null) {
                    return;
                }
                // Convert the live pointer position into the desktop pane's
                // space on every event, anchoring the card to the grab point
                // recorded on press. Recomputing from e.getPoint() keeps the
                // delta stable; re-converting a stored point (the previous
                // convertPointToScreen(press, card)) compounded the card's own
                // movement each event and flung the widget off the cursor.
                Point p = SwingUtilities.convertPoint(card, e.getPoint(), desktop);
                int x = p.x - press.x;
                int y = p.y - press.y;
                int dx = x - origin.x;
                int dy = y - origin.y;
                if (!dragged && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                    dragged = true;
                }
                card.setLocation(x, y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragged) {
                    clampToDesktop(card);
                    float[] f = fractionOf(card);
                    positions.put(instanceId, f);
                    config.setPosition(instanceId, f[0], f[1]);
                    config.save();
                } else {
                    card.onClick();
                }
                press = null;
                origin = null;
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                card.onWheel(e.getWheelRotation());
            }
        };
        card.addMouseListener(handler);
        card.addMouseMotionListener(handler);
        card.addMouseWheelListener(handler);
    }

    private String generateInstanceId(String typeId) {
        String base = typeId + "-" + Long.toString(System.currentTimeMillis(), 36);
        String id;
        do {
            id = base + "-" + (idCounter++);
        } while (cards.containsKey(id) || config.instances().contains(id));
        return id;
    }

    /**
     * Picks a free spot for a new widget: cascades down the left edge, wrapping
     * back to the top when it runs out of room. Mirrors the 3D host's cascade.
     */
    private float[] nextFreeSpot() {
        int n = cards.size();
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

    // ------------------------------------------------------------------
    // Fractional <-> pixel placement
    // ------------------------------------------------------------------

    /** Places {@code card} so its centre sits at the fractional screen point. */
    private void applyBounds(WidgetCard card, float fx, float fy) {
        Dimension pref = card.getPreferredSize();
        int cw = Math.max(1, pref.width);
        int ch = Math.max(1, pref.height);
        int w = desktop.getWidth();
        int h = desktop.getHeight();
        if (w <= 0 || h <= 0) {
            // Not laid out yet; the resize listener re-places it once sized.
            card.setBounds(0, 0, cw, ch);
            return;
        }
        int cx = Math.round(clamp01(fx) * w);
        int cy = Math.round(clamp01(fy) * h);
        int x = clamp(cx - cw / 2, 0, Math.max(0, w - cw));
        int y = clamp(cy - ch / 2, 0, Math.max(0, h - ch));
        card.setBounds(x, y, cw, ch);
    }

    /** Keeps a dragged card fully inside the desktop pane. */
    private void clampToDesktop(WidgetCard card) {
        int w = desktop.getWidth();
        int h = desktop.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int cw = card.getWidth();
        int ch = card.getHeight();
        int x = clamp(card.getX(), 0, Math.max(0, w - cw));
        int y = clamp(card.getY(), 0, Math.max(0, h - ch));
        card.setLocation(x, y);
    }

    /** The fractional (centre) position of {@code card} within the desktop. */
    private float[] fractionOf(WidgetCard card) {
        int w = desktop.getWidth();
        int h = desktop.getHeight();
        Dimension pref = card.getPreferredSize();
        float fx = (w > 0) ? clamp01((card.getX() + pref.width / 2f) / w) : 0f;
        float fy = (h > 0) ? clamp01((card.getY() + pref.height / 2f) / h) : 0f;
        return new float[]{fx, fy};
    }

    private static float clamp01(float v) {
        return (v < 0f) ? 0f : (v > 1f ? 1f : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }

    /** Stops every widget, removes the cards and shuts down the scheduler. */
    public void dispose() {
        desktop.removeComponentListener(resizeListener);
        for (ScheduledFuture<?> tick : ticks.values()) {
            tick.cancel(false);
        }
        ticks.clear();
        for (WidgetCard card : cards.values()) {
            try {
                card.stop();
            } catch (Throwable t) {
                logger.log(Level.FINE, "card dispose failed", t);
            }
            desktop.remove(card);
        }
        cards.clear();
        positions.clear();
        scheduler.shutdownNow();
    }
}
