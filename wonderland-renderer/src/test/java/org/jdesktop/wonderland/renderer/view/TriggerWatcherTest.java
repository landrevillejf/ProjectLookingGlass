/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P1.2: the dispatch the live walk runs on, proven headless.
 *
 * TriggerField's edge arithmetic is already tested in its own suite; what lives
 * here is the small stateful layer the renderer holds between frames -- priming
 * the starting room so entry is not a spurious "enter", forwarding each crossed
 * edge with the walker's real pose, and swallowing a host that throws so a
 * behaviour bug is silence rather than a dropped frame. This is exactly the
 * wiring WorldViewer.apply() calls each eye write, minus the TransformGroup, and
 * none of it needs a display.
 */
package org.jdesktop.wonderland.renderer.view;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.trigger.TriggerZone;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TriggerWatcherTest {

    /** Records every edge the watcher fires, with the pose it was fired at. */
    private static final class Recorder implements TriggerWatcher.Listener {

        final List<String> events = new ArrayList<>();

        @Override
        public void onTrigger(String zoneId, boolean entered,
                float x, float y, float z) {
            events.add((entered ? "enter:" : "exit:") + zoneId
                    + "@" + x + "," + y + "," + z);
        }
    }

    @Test
    void crossingIntoAndOutOfAZoneFiresOneEnterThenOneExit() {
        TriggerWatcher watcher = new TriggerWatcher(0f, 0f, 0f,
                List.of(TriggerZone.open("lobby", 10f, 0f, 2f, 2f)));
        Recorder rec = new Recorder();

        watcher.onEyeAt(10f, 0f, 0f, rec);   // step in
        watcher.onEyeAt(11f, 0f, 0f, rec);   // loiter inside: quiet
        watcher.onEyeAt(30f, 0f, 0f, rec);   // step out

        assertThat(rec.events).containsExactly(
                "enter:lobby@10.0,0.0,0.0", "exit:lobby@30.0,0.0,0.0");
    }

    @Test
    void theRoomYouStartInIsPrimedNotFired() {
        TriggerWatcher watcher = new TriggerWatcher(10f, 0f, 0f,
                List.of(TriggerZone.open("lobby", 10f, 0f, 2f, 2f)));
        Recorder rec = new Recorder();

        assertThat(watcher.currentlyInside()).containsExactly("lobby");
        watcher.onEyeAt(10f, 0f, 0f, rec);   // no move across a boundary

        assertThat(rec.events).as("entry pose is a fact, not an event")
                .isEmpty();
    }

    @Test
    void aListenerThatThrowsCannotStopTheEdgesAfterIt() {
        // Two zones the walker enters at once; "a" throws, "b" must still fire.
        // Start outside both so the move into them is a real crossing edge.
        TriggerWatcher watcher = new TriggerWatcher(5f, 5f, 5f,
                List.of(TriggerZone.open("a", 0f, 0f, 1f, 1f),
                        TriggerZone.open("b", 0f, 0f, 1f, 1f)));
        List<String> reachedB = new ArrayList<>();
        TriggerWatcher.Listener both = (zoneId, entered, x, y, z) -> {
            if (zoneId.equals("a")) {
                throw new IllegalStateException("boom");
            }
            reachedB.add(zoneId);
        };

        // a throwing listener on "a" must not prevent "b" from being dispatched
        watcher.onEyeAt(0.5f, 0f, 0.5f, both);

        assertThat(reachedB).as("the edge after a throwing one still fires")
                .containsExactly("b");
    }

    @Test
    void aNullListenerTracksMembershipButDispatchesNothing() {
        TriggerWatcher watcher = new TriggerWatcher(0f, 0f, 0f,
                List.of(TriggerZone.open("lobby", 10f, 0f, 2f, 2f)));

        watcher.onEyeAt(10f, 0f, 0f, null); // move in, nobody listening

        assertThat(watcher.currentlyInside()).as("still tracks the pose")
                .containsExactly("lobby");
        assertThat(watcher.zoneCount()).isEqualTo(1);
    }

    @Test
    void anEmptyWorldPrimesNoZonesAndFiresNothing() {
        TriggerWatcher watcher = new TriggerWatcher(0f, 0f, 0f, List.of());
        Recorder rec = new Recorder();

        watcher.onEyeAt(50f, 50f, 50f, rec);

        assertThat(watcher.zoneCount()).isZero();
        assertThat(rec.events).isEmpty();
    }
}
