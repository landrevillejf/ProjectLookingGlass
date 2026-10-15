/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P1.2: the enter/exit edge detector over {@link TriggerZone}s.
 *
 * A trigger is defined by its *transition*, not its membership: stepping onto a
 * podium and staying there is one "enter", not an event per frame. So this field
 * remembers which zones the walker was inside on the previous position and, on
 * each {@link #moveTo}, reports only the ids whose membership flipped. That
 * remembered/previous-set design is the whole reason the phase is testable
 * headless -- a test walks a position sequence and asserts the exact enter/exit
 * edges, no clock and no render context involved.
 *
 * The kernel owns no callback dispatch: {@link #moveTo} returns a
 * {@link Transitions} value and the host decides what to do with it (fire a
 * Mandela behaviour from P5, animate, log). Keeping the decision data a return
 * value rather than a live listener means the same object a renderer feeds is
 * the same object a unit test drives.
 */
package org.jdesktop.wonderland.client.trigger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Tracks a walker's position against a set of zones and emits membership edges. */
public final class TriggerField {

    /** The zones entered and exited across one move, in registration order. */
    public record Transitions(List<String> entered, List<String> exited) {

        public static final Transitions NONE = new Transitions(List.of(), List.of());

        public boolean isEmpty() {
            return entered.isEmpty() && exited.isEmpty();
        }
    }

    private final List<TriggerZone> zones = new ArrayList<>();
    private final Set<String> inside = new HashSet<>();
    private float curX;
    private float curY;
    private float curZ;

    /** Seed the walker's starting pose; zones added later prime against it. */
    public TriggerField(float startX, float startY, float startZ) {
        this.curX = startX;
        this.curY = startY;
        this.curZ = startZ;
    }

    /** Register a zone; if the walker already stands inside it, prime it as inside rather than firing a spurious enter. */
    public void addZone(TriggerZone zone) {
        if (zone == null) {
            throw new IllegalArgumentException("zone");
        }
        zones.add(zone);
        if (zone.contains(curX, curY, curZ)) {
            inside.add(zone.id());
        }
    }

    public int zoneCount() {
        return zones.size();
    }

    /** Which zones currently hold the walker. */
    public Set<String> currentlyInside() {
        return new LinkedHashSet<>(inside);
    }

    /**
     * Move the walker to (x, y, z) and report the zones it entered and left. The
     * order of each list follows registration order, so the edges are
     * deterministic and a test can assert them by value.
     */
    public Transitions moveTo(float x, float y, float z) {
        curX = x;
        curY = y;
        curZ = z;
        List<String> entered = new ArrayList<>();
        List<String> exited = new ArrayList<>();
        Set<String> now = new HashSet<>();
        for (TriggerZone z0 : zones) {
            boolean isInside = z0.contains(x, y, z);
            if (isInside) {
                now.add(z0.id());
                if (!inside.contains(z0.id())) {
                    entered.add(z0.id());
                }
            } else if (inside.contains(z0.id())) {
                exited.add(z0.id());
            }
        }
        inside.clear();
        inside.addAll(now);
        return new Transitions(entered, exited);
    }

    /** Re-evaluate from the walker's current pose without moving it (after a world change). */
    public Transitions reassess() {
        return moveTo(curX, curY, curZ);
    }
}
