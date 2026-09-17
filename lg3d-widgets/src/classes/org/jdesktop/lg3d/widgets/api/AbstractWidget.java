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
package org.jdesktop.lg3d.widgets.api;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JPanel;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jogamp.vecmath.Vector3f;

/**
 * Convenient base class for widgets that render a Swing {@link JPanel} in 3D.
 *
 * <p>Subclasses call {@link #setSwingPanel(JPanel)} (usually from
 * {@link #init(WidgetContext)}) to host their UI. The panel is displayed through a
 * {@link SwingNode}; to refresh it after model changes, update the model and call
 * {@link #setDirty()} (which repaints the panel - the SwingNode re-captures it
 * into its texture). Painting from volatile model fields inside the panel's
 * {@code paintComponent} avoids any Swing-threading concerns, since the periodic
 * tick runs on the shared scheduler thread.</p>
 *
 * <p>The SwingNode is made mouse-event propagatable so that, in addition to Swing
 * receiving forwarded input, the widget node itself also receives mouse events.
 * That lets the host attach drag-to-move and click handlers at the widget level
 * without stealing input from the Swing content.</p>
 *
 * <p>Periodic work is scheduled on the shared {@link WidgetContext#scheduler()}
 * via {@link #scheduleTick(long, Runnable)}; a tick that throws is logged and
 * does not cancel the schedule.</p>
 */
public abstract class AbstractWidget extends Component3D implements Widget {
    private static final Logger logger = Logger.getLogger("lg.widgets");

    /** Default quad transparency for hosted Swing content. The SwingNode's own
     *  default (0.8) is far too see-through for a desktop widget, so we render
     *  mostly-opaque; subclasses can adjust via {@link #setSwingTransparency(float)}. */
    private static final float DEFAULT_TRANSPARENCY = 0.12f;

    private final String id;
    private final String displayName;

    private WidgetContext context;
    private String instanceId;

    private SwingNode swingNode;
    private JPanel swingPanel;
    private ScheduledFuture<?> tick;

    protected AbstractWidget(String id, String displayName) {
        this.id = id;
        this.displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
        setName(this.displayName);
        setCursor(Cursor3D.MEDIUM_CURSOR);
    }

    @Override
    public String id() { return id; }

    @Override
    public String displayName() { return displayName; }

    @Override
    public Component3D node() { return this; }

    @Override
    public void init(WidgetContext context) {
        this.context = context;
    }

    /** The context passed to {@link #init(WidgetContext)} (null before init). */
    protected WidgetContext context() { return context; }

    /** The host-assigned instance id, used to namespace persisted options. */
    public String getInstanceId() { return instanceId; }

    /** Assigns the per-desktop instance id (called by the host). */
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    /** Reads a persisted option for this instance. */
    protected String getOption(String key, String def) {
        if (context == null || instanceId == null) {
            return def;
        }
        return context.config().getOption(instanceId, key, def);
    }

    /** Persists an option for this instance. */
    protected void setOption(String key, String value) {
        if (context == null || instanceId == null) {
            return;
        }
        context.config().setOption(instanceId, key, value);
        context.config().save();
    }

    // ------------------------------------------------------------------
    // Swing hosting
    // ------------------------------------------------------------------

    /**
     * Hosts the given panel in a {@link SwingNode} and sizes this widget to match
     * the rendered panel. Call once, typically from {@link #init(WidgetContext)}.
     */
    protected void setSwingPanel(JPanel panel) {
        this.swingPanel = panel;
        this.swingNode = new SwingNode();
        swingNode.setJPanel(panel);
        swingNode.setTransparency(DEFAULT_TRANSPARENCY);
        // Let mouse events reach this widget node as well as the Swing content,
        // so the host can attach drag/click handlers without blocking Swing.
        swingNode.setMouseEventPropagatable(true);
        addChild(swingNode);
        refreshPreferredSize();
    }

    /** Re-sizes this widget to the SwingNode's current rendered extent. */
    protected void refreshPreferredSize() {
        if (swingNode != null) {
            float w = swingNode.getLocalWidth();
            float h = swingNode.getLocalHeight();
            if (w > 0f && h > 0f) {
                setPreferredSize(new Vector3f(w, h, 0f));
            }
        }
    }

    /** The hosted Swing panel, or null if this widget is pure-3D. */
    protected JPanel getSwingPanel() { return swingPanel; }

    /** The hosting SwingNode, or null if this widget is pure-3D. */
    protected SwingNode getSwingNode() { return swingNode; }

    /** Adjusts the quad transparency of the hosted Swing content (0=opaque,1=invisible). */
    protected void setSwingTransparency(float transparency) {
        if (swingNode != null) {
            swingNode.setTransparency(transparency);
        }
    }

    /** Requests a repaint of the hosted Swing panel. Safe from any thread. */
    protected void setDirty() {
        if (swingPanel != null) {
            swingPanel.repaint();
        }
    }

    // ------------------------------------------------------------------
    // Periodic updates
    // ------------------------------------------------------------------

    /**
     * Schedules {@code task} to run immediately and then every
     * {@code periodMillis} on the shared scheduler. Cancels any previous tick.
     * A throwing task is logged and the schedule continues.
     */
    protected void scheduleTick(long periodMillis, Runnable task) {
        cancelTick();
        if (context == null || context.scheduler() == null || periodMillis <= 0) {
            return;
        }
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Widget tick failed: " + displayName, t);
            }
        };
        tick = context.scheduler().scheduleAtFixedRate(
                safe, 0L, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** Cancels the periodic tick, if any. */
    protected void cancelTick() {
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void start() {
        // subclasses override to scheduleTick(...)
    }

    @Override
    public void stop() {
        cancelTick();
    }

    @Override
    public void dispose() {
        stop();
        if (swingNode != null) {
            swingNode.dispose();
            swingNode = null;
        }
        swingPanel = null;
    }

    @Override
    public Vector3f getPreferredSize() {
        return getPreferredSize(new Vector3f());
    }
}
