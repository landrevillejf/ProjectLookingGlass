/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: the seam that turns a screen point into the cells it
 * touches.
 *
 * The live implementation is probe-only: it builds a Jogamp PickCanvas over the
 * desktop canvas (or walks a PickResult's SceneGraphPath), maps each hit node
 * back to the cell that owns it, and returns the cell ids near-to-far. The
 * routing decision itself knows nothing about Java 3D -- it consumes exactly
 * this list -- which is what keeps PickRouter a headless class.
 */
package org.jdesktop.wonderland.desktop;

import java.util.List;

/**
 * Supplies the ordered cell ids under a screen point.
 */
public interface HitSource {

    /**
     * The cells the ray through {@code (screenX, screenY)} passes, nearest
     * first. Ids the source cannot attribute to a mounted cell are simply not
     * in the list; a point over empty space returns an empty (never null) list.
     *
     * @param screenX the pick window's x coordinate in pixels
     * @param screenY the pick window's y coordinate in pixels
     * @return the hit cell ids, near to far; never null, may be empty
     */
    List<String> cellsUnder(double screenX, double screenY);
}
