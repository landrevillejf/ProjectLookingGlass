/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P0.2: the explicit potential-visible-set culler.
 *
 * The 2006 Wonderland left visibility to Java 3D's own bounds culling: every
 * mounted cell was in the scene graph and the graphics pipeline decided what to
 * draw. That works for a demo room and falls over for a large world -- the cost
 * is paid on the whole mounted set before the first triangle is tested, and
 * there is no host-side hook to also gate avatar updates on it (which is why the
 * roadmap calls P0.2 the prerequisite to P5.4's interest management). This
 * kernel is the host-side prefilter: given where the eye is and how far it can
 * see, name the cells worth mounting at all.
 *
 * It is a spatial hash on purpose, not a full octree. The world is bucketed
 * into a regular grid of {@code cellSize}-metre boxes -- the same "octant"
 * subdivision the phase is named for, at one fixed level -- and a query walks
 * only the buckets a sight-sphere touches, then does the exact
 * sphere-vs-cell-sphere overlap on the handful of candidates that survive. A
 * deeper dynamic octree would be more clever and harder to prove correct in a
 * headless test; the grid's behaviour is fully determined by (cellSize), which
 * is exactly what the contract needs to be assertable. A cell's extent is
 * approximated by its bounding sphere (centre + one radius): PVS is a coarse
 * prefilter, and erring generous (mounting a cell that grazes the sight sphere)
 * is safe, while erring strict (dropping a visible cell) would be a hole.
 *
 * Everything here is floats and ids. It imports no Java 3D and no scene-graph
 * type, so the same query the renderer will call is unit-testable in CI with no
 * Display -- the phase's whole verification story.
 */
package org.jdesktop.wonderland.client.cull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A regular-grid (octant) spatial hash over cell bounding spheres that answers
 * "which cells should this eye see?".
 */
public final class OctantCuller {

    /** One registered cell: its id, world centre and a single bounding radius. */
    private record Entry(String id, float x, float y, float z, float radius) {
    }

    private final float cellSize;
    private final List<Entry> entries = new ArrayList<>();
    // bucket key -> indices into {@link entries}. Rebuilt lazily so registering
    // stays allocation-light and a query after N registers costs one pass.
    private java.util.Map<Long, List<Integer>> buckets;

    /**
     * @param cellSize the edge in metres of one octant bucket; must be positive.
     *        A coarse world wants a large bucket (fewer keys, more per-bucket
     *        candidates); a dense one wants a small bucket to make the walk
     *        sparse. The answer of {@link #query} is identical for any
     *        {@code cellSize} -- it only changes the work, never the set.
     */
    public OctantCuller(float cellSize) {
        if (!(cellSize > 0f)) {
            throw new IllegalArgumentException("cellSize must be > 0, was " + cellSize);
        }
        this.cellSize = cellSize;
    }

    /** Register a cell's bounding sphere. Re-registering the same id adds a second volume. */
    public void register(String id, float x, float y, float z, float radius) {
        if (id == null) {
            throw new IllegalArgumentException("id");
        }
        entries.add(new Entry(id, x, y, z, Math.max(0f, radius)));
        buckets = null; // invalidate; rebuilt on the next query
    }

    /** How many volumes the culler knows about. */
    public int size() {
        return entries.size();
    }

    /** Drop every registration; the culler is reusable for the next world load. */
    public void clear() {
        entries.clear();
        buckets = null;
    }

    /**
     * The cells an eye at (eyeX, eyeY, eyeZ) with sight distance {@code radius}
     * should mount: every registered volume whose bounding sphere overlaps the
     * sight sphere. Order is registration order, so the result is deterministic.
     */
    public PotentialVisibleSet query(float eyeX, float eyeY, float eyeZ, float radius) {
        if (entries.isEmpty()) {
            return PotentialVisibleSet.empty();
        }
        ensureBuckets();
        float r = Math.max(0f, radius);
        float r2 = r * r;
        Set<String> visible = new LinkedHashSet<>();
        // The sight sphere spans these bucket indices on each axis; walk the
        // 3D range rather than every entry -- that is the whole point of the grid.
        int minX = bucketCoord(eyeX - r);
        int maxX = bucketCoord(eyeX + r);
        int minY = bucketCoord(eyeY - r);
        int maxY = bucketCoord(eyeY + r);
        int minZ = bucketCoord(eyeZ - r);
        int maxZ = bucketCoord(eyeZ + r);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    List<Integer> list = buckets.get(key(bx, by, bz));
                    if (list == null) {
                        continue;
                    }
                    for (int index : list) {
                        Entry e = entries.get(index);
                        if (overlaps(eyeX, eyeY, eyeZ, r2, e)) {
                            visible.add(e.id());
                        }
                    }
                }
            }
        }
        return new PotentialVisibleSet(visible, entries.size());
    }

    /** Exact sphere/sphere overlap on squared distance (no sqrt on the hot path). */
    private static boolean overlaps(float eyeX, float eyeY, float eyeZ, float r2, Entry e) {
        float dx = e.x() - eyeX;
        float dy = e.y() - eyeY;
        float dz = e.z() - eyeZ;
        float reach = r2 > 0 ? (float) Math.sqrt(r2) : 0f;
        float combined = reach + e.radius();
        return dx * dx + dy * dy + dz * dz <= combined * combined;
    }

    private int bucketCoord(float world) {
        return (int) Math.floor(world / cellSize);
    }

    /**
     * A bucket's three integer coords mixed into one long. Two coords that hash
     * alike only ever share a candidate list, and the exact {@link #overlaps}
     * test drops the intruder, so a collision costs work and never changes the
     * answer -- which is what lets this stay a cheap multiply-xor mix.
     */
    private static long key(int bx, int by, int bz) {
        return (bx * 73856093L) ^ (by * 19349663L) ^ (bz * 83492791L);
    }

    private void ensureBuckets() {
        if (buckets != null) {
            return;
        }
        buckets = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            // A volume spans the buckets its own sphere touches, so a query
            // sphere overlapping it is guaranteed to visit a shared bucket.
            int x0 = bucketCoord(e.x() - e.radius());
            int x1 = bucketCoord(e.x() + e.radius());
            int y0 = bucketCoord(e.y() - e.radius());
            int y1 = bucketCoord(e.y() + e.radius());
            int z0 = bucketCoord(e.z() - e.radius());
            int z1 = bucketCoord(e.z() + e.radius());
            for (int bx = x0; bx <= x1; bx++) {
                for (int by = y0; by <= y1; by++) {
                    for (int bz = z0; bz <= z1; bz++) {
                        buckets.computeIfAbsent(key(bx, by, bz), k -> new ArrayList<>()).add(i);
                    }
                }
            }
        }
    }
}
