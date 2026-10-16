/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P1.2: the live walk's trigger dispatch, kept pure.
 *
 * {@code TriggerField} already answers "which zones did this step cross" as a
 * headless-tested kernel, and {@code WorldScene} can say which volumes a world
 * has. What was missing -- and what this class is -- is the small stateful
 * object the renderer holds between frames: it seeds a field on the pose the
 * walker starts from (so standing in a room on entry is primed, not a spurious
 * "enter"), and on every eye write feeds it the new pose and forwards each
 * crossed edge to a listener. That is the whole of P1.2's wiring except the
 * TransformGroup write, and because none of it touches Java 3D it is proven
 * here, in a plain JVM, the same way P1.1's collider is proven before the walk.
 *
 * The listener is the seam to the P5 behaviour layer: the renderer cannot import
 * Mandela (the dependency rule), so it hands the host the (zone, entered) edge
 * and the host -- {@code WorldRender}, which already runs the world's
 * {@code behaviour.mnd} -- decides what the crossing means. A listener that
 * throws is swallowed exactly like the viewer's close hooks: a behaviour bug is
 * a missing note, never a dropped frame or a dead window.
 */
package org.jdesktop.wonderland.renderer.view;

import java.util.List;
import org.jdesktop.wonderland.client.trigger.TriggerField;
import org.jdesktop.wonderland.client.trigger.TriggerZone;

/** Seeds a {@link TriggerField} on a pose and dispatches each crossed edge to a listener. */
public final class TriggerWatcher {

    /**
     * One zone the walker just crossed; {@code entered} false means they left it,
     * and the trailing pose is where they stood when the boundary flipped -- the
     * host may key a behaviour on the place, not only the name.
     */
    public interface Listener {

        void onTrigger(String zoneId, boolean entered, float x, float y, float z);
    }

    private final TriggerField field;

    /**
     * Prime the field with {@code zones} as of the walker's start pose. A zone the
     * walker already stands inside is remembered as inside, so the first edge a
     * caller sees is a real crossing, not the starting room.
     */
    public TriggerWatcher(float startX, float startY, float startZ,
            List<TriggerZone> zones) {
        field = new TriggerField(startX, startY, startZ);
        if (zones != null) {
            for (TriggerZone zone : zones) {
                if (zone != null) {
                    field.addZone(zone);
                }
            }
        }
    }

    /**
     * Move the walker to (x, y, z) and fire the zones it entered then left. A
     * move that crosses nothing, and a watcher with no listener, do nothing; a
     * listener that throws on one edge cannot stop the edges after it.
     */
    public void onEyeAt(float x, float y, float z, Listener listener) {
        if (listener == null) {
            field.moveTo(x, y, z); // still track membership, just dispatch nothing
            return;
        }
        TriggerField.Transitions crossed = field.moveTo(x, y, z);
        if (crossed.isEmpty()) {
            return;
        }
        for (String id : crossed.entered()) {
            safely(listener, id, true, x, y, z);
        }
        for (String id : crossed.exited()) {
            safely(listener, id, false, x, y, z);
        }
    }

    private static void safely(Listener listener, String zoneId, boolean entered,
            float x, float y, float z) {
        try {
            listener.onTrigger(zoneId, entered, x, y, z);
        } catch (RuntimeException | Error behaviourThrew) {
            // The walk goes on; a behaviour that failed to speak is silence,
            // not a frame dropped or a window lost.
        }
    }

    /** How many zones this watcher tracks -- the probe's evidence the field is seeded. */
    public int zoneCount() {
        return field.zoneCount();
    }

    /** The zones currently holding the walker; the state a test walks against. */
    public java.util.Set<String> currentlyInside() {
        return field.currentlyInside();
    }
}
