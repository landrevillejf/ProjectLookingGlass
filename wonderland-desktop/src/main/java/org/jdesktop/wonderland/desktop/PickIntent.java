/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: the input vocabulary the desktop's pick layer routes.
 *
 * The 2006 client spelled its pick semantics in the event classes it delivered
 * (select / drag / go-to / hover); the desktop bridge does not port those
 * classes -- it only needs their *intent*, because the routing decision is
 * "which cell, if any, owns this gesture", not "what Java 3D event arrived".
 * Keeping the four intents in one enum is what lets the decision layer
 * (PickRouter) be tested headless: a probe feeds real coordinates and Java 3D
 * PickResults, a CI test feeds a recorded list of cell ids.
 */
package org.jdesktop.wonderland.desktop;

/**
 * The gesture a pick carries into the wonderland world layer.
 */
public enum PickIntent {

    /** Point-to-select: the user wants a cell as the selection. */
    SELECT,

    /** Press-and-move: the cell is the mover (a cell or avatar translation). */
    DRAG,

    /** Move-to: the avatar walks to the picked spot, so the pick selects a
        destination rather than an object. */
    GOTO,

    /** No-button motion: cursor feedback over a cell. */
    HOVER
}
