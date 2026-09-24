/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.paint;

import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import javax.swing.Icon;

/**
 * A Paint tool. The active tool receives the mouse gestures that
 * {@link PaintCanvas} translates into document coordinates and reacts through
 * the shared {@link PaintContext} (document, tool settings, selection and the
 * canvas callbacks).
 *
 * <p>The gesture lifecycle is {@link #press} -&gt; zero or more {@link #drag}
 * -&gt; {@link #release}. Freehand tools paint straight into the active layer
 * as they go; shape tools keep their geometry in fields and render it through
 * {@link #preview} (an overlay the canvas paints on top of the composite) until
 * {@link #release} commits it to the layer. {@link #activated}/{@link #deactivated}
 * bracket a tool's time as the current tool.</p>
 */
public interface Tool {

    /** The tool name, also the key {@link PaintIcons} uses for its icon. */
    String getName();

    /** A one-line status-bar hint describing what the tool does. */
    String getHint();

    /** The cursor shown while this tool is active (null for the default). */
    Cursor getCursor();

    /** The toolbox icon, built at runtime by {@link PaintIcons}. */
    Icon getIcon();

    void press(Point2D p, PaintContext ctx);

    void drag(Point2D p, PaintContext ctx);

    void release(Point2D p, PaintContext ctx);

    /**
     * Paints this tool's live preview (marquee, rubber-band shape, text caret)
     * onto {@code g}, which is already transformed into document space. The
     * default paints nothing.
     */
    void preview(Graphics2D g, PaintContext ctx);

    void activated(PaintContext ctx);

    void deactivated(PaintContext ctx);
}
