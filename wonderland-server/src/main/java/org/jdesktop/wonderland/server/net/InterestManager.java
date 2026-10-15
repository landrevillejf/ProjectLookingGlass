/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: who is told about what.
 *
 * Roadmap P0.2 built the client-side half of interest management -- an
 * octant culler a FRAME consults -- and P2.4 gave it a lifecycle owner; the
 * server-side half was left unbuilt, and this class is it, deliberately NOT
 * a copy of WorldPVS: the module's own
 * dependency rule (see wonderland-server/build.gradle, "No wonderland-client
 * type is imported") forbids importing the client's culler, and the two
 * queries are different anyway. A client culls CELLS by its own eye; a server
 * fans to SESSIONS by their avatars' positions -- n is the population of the
 * world, not the size of it, so an honest O(n) scan per event beats a grid
 * that has to be kept in sync with a moving, logging-out, teleporting set of
 * people. When a world ever holds enough concurrent sessions for the scan to
 * matter, the octant kernel's shape ports over; that is a measured problem,
 * not a designed-in one.
 *
 * The generosity rule matches the client's: an event reaches a session when
 * the session's watch sphere reaches the event's own radius -- the union of
 * the two. An omitted recipient is a person who cannot see the world change;
 * an extra one is a packet. The server picks packets.
 */
package org.jdesktop.wonderland.server.net;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Which connected sessions care about a place in the world (roadmap P5.4). */
public final class InterestManager {

    /** One session's eye: where its avatar is and how far it watches. */
    private record Watch(float x, float y, float z, float radius) {
    }

    private final Map<BigInteger, Watch> watches = new ConcurrentHashMap<>();

    /**
     * (Re-)place a session's watch. The same call answers a first view and a
     * move -- a MovableMessage's server-side twin -- because a watch, like a
     * transform, is only ever replaced, never patched. A negative radius is
     * the caller's slip, clamped; a NaN place is refused, because a
     * comparison with NaN silently excludes everyone.
     */
    public void watch(BigInteger sessionId, float x, float y, float z, float radius) {
        if (sessionId == null) {
            throw new NullPointerException("an interest belongs to a session");
        }
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
            throw new IllegalArgumentException("a watch point must be a place");
        }
        watches.put(sessionId, new Watch(x, y, z, Math.max(0f, radius)));
    }

    /** A session left; its watch leaves with it, no ghost recipients. */
    public void forget(BigInteger sessionId) {
        watches.remove(sessionId);
    }

    /** How many sessions are watched; a router's log line, and a test's truth. */
    public int size() {
        return watches.size();
    }

    /**
     * Whether a session has registered a watch yet. This is the router's
     * default-open key: a session that has never moved its avatar is untracked,
     * and an untracked session must still receive every event -- presence has to
     * survive the moments before the first move lands, and a login that was
     * never spatially filtered cannot be silently starved. Spatial filtering
     * engages only once a session is tracked, so wiring the audience in changes
     * nothing until avatars actually move.
     */
    public boolean isTracked(BigInteger sessionId) {
        return sessionId != null && watches.containsKey(sessionId);
    }

    /**
     * The audience for one event: every session whose watch sphere (its own
     * radius) reaches within {@code eventRadius} of the event's place --
     * distances are compared squared, because this runs per update and a
     * sqrt buys nothing an epsilon cannot. Order is unspecified; the router
     * fans out anyway.
     */
    public List<BigInteger> audienceFor(float x, float y, float z, float eventRadius) {
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
            throw new IllegalArgumentException("an event must happen somewhere");
        }
        float reachFloor = Math.max(0f, eventRadius);
        List<BigInteger> audience = new ArrayList<>();
        for (Map.Entry<BigInteger, Watch> entry : watches.entrySet()) {
            Watch w = entry.getValue();
            double dx = w.x() - x;
            double dy = w.y() - y;
            double dz = w.z() - z;
            double reach = w.radius() + reachFloor;
            if (dx * dx + dy * dy + dz * dz <= (double) reach * reach) {
                audience.add(entry.getKey());
            }
        }
        return audience;
    }
}
