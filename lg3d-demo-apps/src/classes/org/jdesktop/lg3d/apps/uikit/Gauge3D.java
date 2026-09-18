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
package org.jdesktop.lg3d.apps.uikit;

import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Color4f;

/**
 * A horizontal gauge (the lg3d-native stand-in for a
 * {@code JProgressBar}): a translucent track with a fill bar anchored at the
 * left edge whose x-scale tracks a 0..1 value.
 *
 * <p>The fill is a full-width panel shifted so its left edge sits on the
 * gauge origin; scaling the wrapper about that origin therefore grows the bar
 * rightward without rebuilding geometry.</p>
 */
public class Gauge3D extends Component3D {

    private final Component3D fill;

    public Gauge3D(float w, float h, Color4f track, Color4f fillColor) {
        addChild(Ui3D.component(
                Ui3D.at(Ui3D.panel(w, h, 0.002f, track), 0f, 0f, -0.002f)));
        // Left-anchored fill: inner panel centred at +w/2, so the wrapper's
        // origin is the bar's left edge.
        fill = Ui3D.component(
                Ui3D.at(Ui3D.panel(w, h * 0.72f, 0.002f, fillColor), w * 0.5f, 0f, 0.001f));
        addChild(fill);
        setFill(0.0f);
    }

    /** Sets the filled fraction, clamped to 0..1. */
    public void setFill(float fraction) {
        float f = Math.min(1.0f, Math.max(0.001f, fraction));
        fill.setScale(f, 1.0f, 1.0f);
    }
}
