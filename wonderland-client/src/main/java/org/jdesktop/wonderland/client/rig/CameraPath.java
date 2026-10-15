/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P3.1: the cinematic move, a spline through waypoints.
 *
 * FollowRig smooths the eye chasing a SUBJECT; the other half of a cinematic
 * system is the eye travelling a CHOREOGRAPHY -- a tour of the clinic, a
 * swoop over the pond the water kernel just surfaced. The 2006 client keyed
 * camera positions and interpolated between them; this port picks Catmull-Rom
 * for headless reasons: it passes EXACTLY through every control point (a tour
 * must actually visit the room it claims to), it needs no solver or matrix
 * decomposition -- four points and a cubic per segment, arithmetic a test can
 * pin -- and it is C1 continuous, so the eye's velocity does not click at the
 * seams, which is the difference between a swoop and a stumble.
 *
 * t is normalised over the whole path, not per segment: segment i covers
 * [i/(n-1), (i+1)/(n-1)] of it, so a shell's clock feeds one number in [0, 1]
 * and the path decides where that second sits on the route. The ends are the
 * documented Catmull-Rom lie: the first and last segments duplicate their end
 * waypoint as the phantom neighbour, which pulls the tangent along the path
 * instead of letting the curve sag off into nothing.
 */
package org.jdesktop.wonderland.client.rig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A Catmull-Rom camera path through at least two waypoints (roadmap P3.1). */
public final class CameraPath {

    /** One waypoint: where the eye sits there, which the path promises to hit. */
    public record Waypoint(float x, float y, float z) {

        public Waypoint {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("a waypoint must be a place: " + x + "," + y + "," + z);
            }
        }
    }

    private final List<Waypoint> waypoints;

    public CameraPath(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            throw new IllegalArgumentException("a path between one point is a lie about travel");
        }
        this.waypoints = List.copyOf(waypoints);
    }

    /** The route as authored, for a shell that wants to draw or log it. */
    public List<Waypoint> waypoints() {
        return waypoints;
    }

    /**
     * The eye's place at t in [0, 1] over the whole path. t is clamped, not
     * refused -- a frame clock that overshoots the end of a tour should get
     * the last view, not an exception on the render thread (the audio path's
     * policy, applied to the camera's). The math is the standard uniform
     * Catmull-Rom cubic, evaluated per coordinate.
     */
    public Waypoint sample(float t) {
        float u = Math.max(0f, Math.min(1f, t));
        int segments = waypoints.size() - 1;
        int i = Math.min((int) (u * segments), segments - 1); // last t lands in last segment
        float local = u * segments - i;

        Waypoint p0 = waypoints.get(Math.max(0, i - 1)); // the documented end clamp
        Waypoint p1 = waypoints.get(i);
        Waypoint p2 = waypoints.get(i + 1);
        Waypoint p3 = waypoints.get(Math.min(segments, i + 2));

        return new Waypoint(
                catmullRom(p0.x(), p1.x(), p2.x(), p3.x(), local),
                catmullRom(p0.y(), p1.y(), p2.y(), p3.y(), local),
                catmullRom(p0.z(), p1.z(), p2.z(), p3.z(), local));
    }

    /**
     * Where the eye should LOOK from a point on the path: straight along the
     * route's tangent, so a tour faces where it is going. At the tour's own
     * ends the centred difference becomes one-sided (there is no frame past
     * the start or the finish), and a zero tangent -- two identical
     * waypoints -- answers +Z rather than a direction that cannot be
     * normalised.
     */
    public float[] lookDirection(float t) {
        float u = Math.max(0f, Math.min(1f, t));
        float ahead = Math.min(u + 1e-3f, 1f);
        float behind = Math.max(u - 1e-3f, 0f);
        Waypoint f = sample(ahead);
        Waypoint b = sample(behind);
        double dx = f.x() - b.x();
        double dy = f.y() - b.y();
        double dz = f.z() - b.z();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 1e-6) {
            return new float[] {0f, 0f, 1f}; // the flat-spot hold: +Z beats NaN
        }
        return new float[] {(float) (dx / len), (float) (dy / len), (float) (dz / len)};
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        double t2 = t * (double) t;
        double t3 = t2 * t;
        return (float) (0.5 * ((2.0 * p1)
                + (-p0 + p2) * t
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3));
    }

    /** A convenience for the call sites that read more like a tour than a list. */
    public static CameraPath of(Waypoint... points) {
        return new CameraPath(new ArrayList<>(List.of(Objects.requireNonNull(points))));
    }
}
