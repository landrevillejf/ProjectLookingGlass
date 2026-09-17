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

import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Vector3f;

/**
 * A pluggable desktop widget.
 *
 * <p>A widget is a small, self-updating 3D component that lives on the desktop
 * layer (in front of the background, behind application windows). Implementations
 * almost always extend {@link AbstractWidget}, which supplies the Swing-on-3D
 * hosting, the shared update tick and the drag-to-move plumbing; this interface
 * is the minimal contract the host layer programs against.</p>
 *
 * <p>Lifecycle, in order:</p>
 * <ol>
 *   <li>{@link #init(WidgetContext)} - once, right after construction, before the
 *       node is added to the scene. The widget builds its content here.</li>
 *   <li>{@link #start()} - when the widget becomes live on the desktop; start any
 *       periodic updates.</li>
 *   <li>{@link #stop()} - when the widget is removed or the desktop shuts down;
 *       cancel updates but keep state so {@link #start()} can resume.</li>
 *   <li>{@link #dispose()} - final teardown; release the SwingNode and any other
 *       resources. The widget is not reused after this.</li>
 * </ol>
 */
public interface Widget {

    /** The widget type id, matching the {@link WidgetDescriptor#id()} that created it. */
    String id();

    /** A short, human-readable name shown in the gallery and tooltips. */
    String displayName();

    /**
     * Initializes the widget with its runtime context. Called once before the
     * widget's {@link #node()} is added to the scene graph.
     */
    void init(WidgetContext context);

    /** Called when the widget becomes live; begin periodic updates here. */
    void start();

    /** Called when the widget is hidden or the desktop is shutting down. */
    void stop();

    /** Final teardown; releases all resources held by this widget. */
    void dispose();

    /** The 3D scene-graph node representing this widget (never null after init). */
    Component3D node();

    /** The widget's preferred extent in physical (meter) units. */
    Vector3f getPreferredSize();
}
