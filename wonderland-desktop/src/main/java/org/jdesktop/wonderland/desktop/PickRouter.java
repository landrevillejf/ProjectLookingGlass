/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: the pick / input routing decision layer.
 *
 * The 2006 client owned this logic inside its Java 3d input stage: a pick
 * walked the hit list near-to-far and stopped at the first cell that was loaded,
 * shown, and actually listening for that event class. The port keeps the same
 * three-part rule, but as a pure decision over the ids the HitSource reports --
 * no PickResult, no Java 3D, so every branch is asserted in CI.
 *
 * The mount / visibility truth lives in CellFrameBridge (it is the only object
 * that knows what is really in the scene graph), and the per-cell interests live
 * here. Both are consulted per hit, in order, and the first hit that satisfies
 * all three wins; anything else is a passthrough, which is what lets the desktop
 * keep its own window gestures over empty world space.
 */
package org.jdesktop.wonderland.desktop;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routes a pick on the world layer to the cell that owns it, or hands it back
 * to the desktop.
 *
 * <p>Thread safety: the desktop delivers picks on the render thread and edits
 * interests from the session thread, so the interest map is synchronised and the
 * hit list is read once per decision.</p>
 */
public final class PickRouter {

    private final CellFrameBridge bridge;
    private final HitSource hits;

    /** cell id -> the intents that cell listens for, in registration order. */
    private final Map<String, Set<PickIntent>> interests = new LinkedHashMap<>();

    /**
     * The router for a caller that resolves the hit list itself -- the desktop's
     * picked mouse events hand over the cells, so no point pick is ever asked
     * for. Every {@link #route} then stays a passthrough, which is the right
     * answer for a layer that cannot pick, not an error.
     *
     * @param bridge the mount / visibility truth; must not be null
     */
    public PickRouter(CellFrameBridge bridge) {
        this(bridge, null);
    }

    /**
     * @param bridge the mount / visibility truth; must not be null
     * @param hits   the source that reports the cells under a point; may be
     *               null while no canvas exists yet, which makes every pick a
     *               passthrough rather than a failure
     */
    public PickRouter(CellFrameBridge bridge, HitSource hits) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        this.bridge = bridge;
        this.hits = hits;
    }

    /**
     * Declares that {@code cellId} takes the given gestures. Registering the
     * same cell again adds to its set, so a listener can claim more intents
     * without re-registering the ones it already has.
     *
     * @param cellId  the mounted cell
     * @param intents the gestures that cell owns; at least one
     */
    public synchronized void register(String cellId, PickIntent... intents) {
        if (cellId == null || cellId.isEmpty()) {
            throw new IllegalArgumentException("cellId must not be null or empty");
        }
        if (intents == null || intents.length == 0) {
            throw new IllegalArgumentException("at least one intent is required");
        }
        Set<PickIntent> mine = interests.computeIfAbsent(cellId,
                key -> Collections.synchronizedSet(EnumSet.noneOf(PickIntent.class)));
        for (PickIntent intent : intents) {
            if (intent == null) {
                throw new IllegalArgumentException("intent must not be null");
            }
            mine.add(intent);
        }
    }

    /**
     * Drops every intent declared for {@code cellId}. Unmounting a cell does
     * <em>not</em> call this: a cell that unloads and reloads keeps the gestures
     * it asked for, exactly as the 2006 client's listeners survived a reload.
     *
     * @param cellId the cell to stop routing to
     */
    public synchronized void unregister(String cellId) {
        if (cellId != null) {
            interests.remove(cellId);
        }
    }

    /** Whether {@code cellId} has declared {@code intent}. */
    public synchronized boolean isInterested(String cellId, PickIntent intent) {
        Set<PickIntent> mine = interests.get(cellId);
        return mine != null && mine.contains(intent);
    }

    /** The cells that declared at least one intent, in registration order. */
    public synchronized Set<String> registeredCells() {
        return new LinkedHashSet<>(interests.keySet());
    }

    /** Forget every declared intent (module dispose). */
    public synchronized void clear() {
        interests.clear();
    }

    /**
     * The routing decision for the point {@code (screenX, screenY)}.
     *
     * @param screenX pick window x in pixels
     * @param screenY pick window y in pixels
     * @param intent  the gesture being routed
     * @return the owning cell, or a passthrough when no cell claims the gesture
     */
    public PickDecision route(double screenX, double screenY, PickIntent intent) {
        if (intent == null || hits == null) {
            return PickDecision.passthrough(intent);
        }
        List<String> hitCells;
        try {
            hitCells = hits.cellsUnder(screenX, screenY);
        } catch (RuntimeException | Error probeFailure) {
            // A pick on a canvas that is being torn down must not take the
            // desktop's input thread with it: the gesture stays with the
            // desktop, which is the same answer empty space gives.
            return PickDecision.passthrough(intent);
        }
        return routeHits(hitCells, intent);
    }

    /**
     * The same decision for a caller that already resolved the hit list -- the
     * probe path, which picks through Java 3D and hands the ids over.
     *
     * @param hitCells the cells under the pointer, near to far (may be null)
     * @param intent   the gesture being routed
     * @return the owning cell, or a passthrough
     */
    public PickDecision routeHits(Collection<String> hitCells, PickIntent intent) {
        if (intent == null || hitCells == null) {
            return PickDecision.passthrough(intent);
        }
        for (String cellId : hitCells) {
            if (cellId == null) {
                continue;
            }
            if (!bridge.isMounted(cellId) || !bridge.isVisible(cellId)) {
                continue;
            }
            if (isInterested(cellId, intent)) {
                return PickDecision.routeTo(cellId, intent);
            }
        }
        return PickDecision.passthrough(intent);
    }
}
