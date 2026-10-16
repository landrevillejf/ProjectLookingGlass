/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps wonderland bridge (M6c2; moved out of wonderland-desktop): the
 * desktop-side half of pick / input routing.
 *
 * lg3d owns the picking. Its PickEngine walks the canvas, and the desktop hands
 * an app a MouseEvent3D whose intersected nodes are ordered near to far -- which
 * is why Java3DGraph.addJ3dChild() sets the PickTool capabilities on the way in.
 * What the world layer still needed is the translation: from "the nodes this
 * gesture hit" to "the cell that owns the gesture", and that decision is already
 * made by the pure PickRouter. This class is only the adapter between the two
 * vocabularies, plus the guarantee the desktop's event thread relies on: an
 * input path that throws is an input path that kills the shell, so every failure
 * here is swallowed and the gesture stays with the desktop.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.util.List;
import java.util.function.Consumer;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.MouseButtonEvent3D;
import org.jdesktop.lg3d.wg.event.MouseDraggedEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEnteredEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEvent3D;
import org.jdesktop.lg3d.wg.event.MouseMovedEvent3D;
import org.jdesktop.lg3d.wg.event.MouseMotionEvent3D;
import org.jdesktop.wonderland.desktop.PickDecision;
import org.jdesktop.wonderland.desktop.PickIntent;
import org.jdesktop.wonderland.desktop.PickRouter;

/**
 * Turns the desktop's picked mouse events into {@link PickDecision}s and hands
 * them to the cell layer.
 */
public final class WorldInputRouter implements LgEventListener {

    /** The gestures a cell can be given; anything else stays with the desktop. */
    private static final Class<?>[] ROUTED_EVENTS = {
        MouseButtonEvent3D.class,
        MouseDraggedEvent3D.class,
        MouseMovedEvent3D.class,
        MouseEnteredEvent3D.class,
    };

    private final WonderlandWorldContainer container;
    private final PickRouter router;
    private final Consumer<PickDecision> sink;

    /**
     * @param container the live world layer, which maps picked nodes to cells
     * @param router    the routing decision layer
     * @param sink      where the decision goes; may be null, which means the
     *                  routing runs but nothing consumes the result (the probe
     *                  uses this to prove the path without a cell UI)
     */
    public WorldInputRouter(WonderlandWorldContainer container, PickRouter router,
            Consumer<PickDecision> sink) {
        if (container == null) {
            throw new IllegalArgumentException("container must not be null");
        }
        if (router == null) {
            throw new IllegalArgumentException("router must not be null");
        }
        this.container = container;
        this.router = router;
        this.sink = sink;
    }

    /**
     * The gesture an lg3d event class stands for, or null when the class is not
     * the world's business.
     *
     * <p>Order matters: the concrete classes are tested before their abstract
     * parents, because a drag is also a mouse-motion event and a wheel turn is
     * also a mouse event.</p>
     *
     * <p>The abstract {@link MouseMotionEvent3D} is answered rather than the
     * concrete {@link MouseMovedEvent3D} on purpose: lg3d's own
     * {@code MouseMotionEvent3D} subclasses are exactly Moved and Dragged, so a
     * motion that does not claim a button is a hover whether it arrives as the
     * concrete class or as the class a listener registered.</p>
     *
     * @param eventClass the lg3d event class, may be null
     * @return the intent to route, or null to leave the gesture with the desktop
     */
    public static PickIntent intentFor(Class<?> eventClass) {
        if (eventClass == null) {
            return null;
        }
        if (MouseDraggedEvent3D.class.isAssignableFrom(eventClass)) {
            return PickIntent.DRAG;
        }
        if (MouseMotionEvent3D.class.isAssignableFrom(eventClass)
                || MouseEnteredEvent3D.class.isAssignableFrom(eventClass)) {
            return PickIntent.HOVER;
        }
        if (MouseButtonEvent3D.class.isAssignableFrom(eventClass)) {
            return PickIntent.SELECT;
        }
        // MouseWheelEvent3D (zoom) and every other gesture belong to the
        // desktop's own scene manager: empty world space must still scroll.
        return null;
    }

    @Override
    public Class[] getTargetEventClasses() {
        return routedEventClasses();
    }

    /**
     * The lg3d event classes the world layer claims. Public so the declaration
     * can be checked against the mapping: a class registered here that
     * {@link #intentFor} cannot read would be a listener that never hears
     * anything, and nothing in a headless test would notice.
     */
    public static Class<?>[] routedEventClasses() {
        return ROUTED_EVENTS.clone();
    }

    @Override
    public void processEvent(LgEvent evt) {
        if (!(evt instanceof MouseEvent3D mouseEvent)) {
            return;
        }
        PickIntent intent = intentFor(evt.getClass());
        if (intent == null) {
            return;
        }
        try {
            List<String> hitCells = container.cellIdsFor(mouseEvent);
            PickDecision decision = router.routeHits(hitCells, intent);
            if (sink != null) {
                sink.accept(decision);
            }
        } catch (RuntimeException | Error eventThreadFailure) {
            // Never escape onto the desktop's event thread: a world layer that
            // cannot answer leaves the gesture where it started.
        }
    }
}
