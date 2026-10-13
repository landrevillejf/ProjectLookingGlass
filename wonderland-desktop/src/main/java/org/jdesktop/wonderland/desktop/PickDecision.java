/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: the answer a pick produces.
 *
 * The desktop has to decide one thing before it can dispatch a gesture: does
 * the wonderland world layer own it, or does the rest of the desktop? That is
 * why the decision carries a "consumed" flag next to the cell id -- a passthrough
 * is a real answer, and the caller must not have to infer it from a null.
 */
package org.jdesktop.wonderland.desktop;

/**
 * The outcome of routing one pick.
 *
 * @param cellId  the cell that owns the gesture, or null when no cell does
 * @param intent  the gesture that was routed
 * @param consumed true when the world layer takes the gesture (so the desktop
 *                 must not treat the same event as a background gesture)
 */
public record PickDecision(String cellId, PickIntent intent, boolean consumed) {

    /**
     * A decision that hands the gesture back to the desktop: no cell claimed
     * it, so the caller may move the window layer, the wallpaper, the dock.
     *
     * @param intent the gesture that found no owner
     * @return a non-consuming decision
     */
    public static PickDecision passthrough(PickIntent intent) {
        return new PickDecision(null, intent, false);
    }

    /**
     * A decision that gives the gesture to {@code cellId}.
     *
     * @param cellId the owning cell; must not be null
     * @param intent the gesture being routed
     * @return a consuming decision
     */
    public static PickDecision routeTo(String cellId, PickIntent intent) {
        if (cellId == null) {
            throw new IllegalArgumentException("cellId must not be null");
        }
        return new PickDecision(cellId, intent, true);
    }
}
