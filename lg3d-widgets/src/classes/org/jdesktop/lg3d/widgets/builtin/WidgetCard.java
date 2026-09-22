/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.widgets.builtin;

import java.util.concurrent.Executor;
import org.jdesktop.lg3d.widgets.api.WidgetConfigStore;

/**
 * The pure-Swing face of a desktop widget: a glassy card that owns its own
 * model, its periodic {@link #tick()} update, its painting and its click /
 * mouse-wheel interaction.
 *
 * <p>A card holds <em>no</em> reference to Java 3D, so the same card renders
 * both inside the 3D desktop (where an {@code AbstractWidget} wraps it in a
 * {@code SwingNode}) and inside the conventional Swing 2D desktop (where
 * {@code SwingWidgetLayer} drops it straight onto the desktop pane). All the
 * widget's logic lives here once; the two hosts only differ in how they place,
 * drive and move it.</p>
 *
 * <p>The host calls {@link #attach(WidgetConfigStore, String, Executor)} once
 * before {@link #start()}, handing the card the persistence store, its
 * per-desktop instance id and an executor for off-EDT work. It then calls
 * {@link #tick()} on a shared scheduler every {@link #tickPeriodMillis()}; a
 * tick updates {@code volatile} model fields and calls {@link #repaint()}, so
 * no Swing-thread hand-off is needed. Interaction is funnelled through
 * {@link #onClick()} / {@link #onWheel(int)} so the host can distinguish a
 * click from a drag.</p>
 */
public abstract class WidgetCard extends WidgetPanel {

    private WidgetConfigStore config;
    private String instanceId;
    private Executor executor;

    protected WidgetCard(String title, int width, int height) {
        super(title, width, height);
    }

    /** The widget type id, matching the persisted {@code type} and the spec. */
    public abstract String id();

    /**
     * How often the host should call {@link #tick()}, in milliseconds. Zero or
     * less means the card has no periodic update.
     */
    public long tickPeriodMillis() {
        return 0L;
    }

    /**
     * Refreshes the model and repaints. Runs on the host's scheduler thread, so
     * it may block (e.g. a network fetch); it must publish results to
     * {@code volatile} fields and call {@link #repaint()} rather than touching
     * Swing state directly.
     */
    public void tick() {
        // no periodic update by default
    }

    /** Called once when the card becomes live on the desktop. */
    public void start() {
        // nothing by default
    }

    /** Called when the card is removed; stop work but keep state. */
    public void stop() {
        // nothing by default
    }

    /** A click that the host has determined was not a drag. */
    public void onClick() {
        // not interactive by default
    }

    /** A mouse-wheel gesture over the card. */
    public void onWheel(int rotation) {
        // not interactive by default
    }

    /**
     * Binds the persistence store, this instance's id and an executor for
     * off-EDT work, then runs {@link #onAttach()}. Called by the host once,
     * before {@link #start()}. {@code config} or {@code instanceId} may be null
     * when a card is shown without persistence (e.g. a gallery preview).
     */
    public void attach(WidgetConfigStore config, String instanceId, Executor executor) {
        this.config = config;
        this.instanceId = instanceId;
        this.executor = executor;
        onAttach();
    }

    /** Hook for subclasses to read persisted options once {@link #attach} runs. */
    protected void onAttach() {
        // nothing by default
    }

    /** The bound config store, or null when not persisted. */
    protected final WidgetConfigStore config() {
        return config;
    }

    /** This instance's id, or null when not persisted. */
    protected final String instanceId() {
        return instanceId;
    }

    /** The host-provided executor for off-EDT work, or null. */
    protected final Executor executor() {
        return executor;
    }

    /** Reads a persisted option for this instance. */
    protected final String getOption(String key, String def) {
        if (config == null || instanceId == null) {
            return def;
        }
        return config.getOption(instanceId, key, def);
    }

    /** Persists an option for this instance. */
    protected final void setOption(String key, String value) {
        if (config == null || instanceId == null) {
            return;
        }
        config.setOption(instanceId, key, value);
        config.save();
    }
}
