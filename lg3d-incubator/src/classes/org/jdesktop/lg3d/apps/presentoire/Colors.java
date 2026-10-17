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
package org.jdesktop.lg3d.apps.presentoire;

import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jogamp.vecmath.Color4f;

/**
 * The 3D-side colour bridge: it adapts the AWT-free {@link ModelColor} parser to
 * the {@code org.jogamp.vecmath.Color4f} the scene graph wants.
 *
 * <p>The single source of truth for what a {@code color="..."} attribute means is
 * {@link ModelColor}, shared with the 2D panel; this class only converts its
 * packed {@code 0xAARRGGBB} int into a {@code Color4f}. The original resolved a
 * name by reflecting over this class's own static fields and divided each channel
 * by {@code 254}; both are gone - the parse (names, {@code #RRGGBB},
 * {@code #RRGGBBAA}) now lives in {@link ModelColor}, which divides by
 * {@code 255} and never throws.</p>
 *
 * @author Pierre Ducroquet
 */
public class Colors {

    /** Opaque red. */
    public static final Color4f RED = toColor4f(ModelColor.RED);
    /** Opaque green. */
    public static final Color4f GREEN = toColor4f(ModelColor.GREEN);
    /** Opaque blue. */
    public static final Color4f BLUE = toColor4f(ModelColor.BLUE);
    /** Opaque white. */
    public static final Color4f WHITE = toColor4f(ModelColor.WHITE);
    /** Opaque black - the fallback for an unrecognised spec. */
    public static final Color4f BLACK = toColor4f(ModelColor.BLACK);

    private Colors() {
        // no instances
    }

    /**
     * Parses a Presentoire colour spec and returns it as a {@code Color4f}.
     *
     * @param color the colour attribute value, may be null
     * @return the colour, or {@link #BLACK} when the spec is null, blank or
     *         unrecognised (never null, never throws)
     */
    public static Color4f getColor(String color) {
        return toColor4f(ModelColor.parse(color));
    }

    /**
     * Converts an already-packed {@code 0xAARRGGBB} colour (as carried by the
     * AWT-free model) into a {@code Color4f} for the scene graph.
     *
     * @param argb the packed colour
     * @return the equivalent {@code Color4f}, never null
     */
    public static Color4f getColor(int argb) {
        return toColor4f(argb);
    }

    /** Builds a {@code Color4f} from a packed {@code 0xAARRGGBB} int. */
    private static Color4f toColor4f(int argb) {
        return new Color4f(ModelColor.redFloat(argb), ModelColor.greenFloat(argb),
                ModelColor.blueFloat(argb), ModelColor.alphaFloat(argb));
    }
}
