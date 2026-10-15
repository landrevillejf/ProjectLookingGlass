/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P0.2: the answer of one cull query.
 *
 * The set of cells an eye at a point should be shown -- nothing more. It is a
 * value, not a live scene graph handle: the renderer (wonderland-renderer) will
 * drive its mount/unmount off {@link #ids()}, and a headless test can assert the
 * membership without a Display. Keeping it a set of ids plus its bounding
 * counts is what lets the same query the renderer calls be proven correct in
 * CI, which is the reason this phase lands as a pure kernel first.
 *
 * The two counts are not decoration: {@link #total()} against
 * {@link #visibleCount()} is the ratio the perf story is measured by, and a
 * culler that returned everything would pass every membership test while
 * defeating its own purpose -- so the ratio is part of the returned value and
 * can be asserted directly.
 */
package org.jdesktop.wonderland.client.cull;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** The immutable result of one {@link OctantCuller} query. */
public final class PotentialVisibleSet {

    private final Set<String> ids;
    private final int total;

    PotentialVisibleSet(Set<String> ids, int total) {
        this.ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        this.total = total;
    }

    /** An empty answer over an empty world. */
    public static PotentialVisibleSet empty() {
        return new PotentialVisibleSet(Collections.emptySet(), 0);
    }

    /** The cell ids that survive the cull, in insertion order. */
    public Set<String> ids() {
        return ids;
    }

    /** Whether a given cell should be shown to this eye. */
    public boolean contains(String id) {
        return ids.contains(id);
    }

    /** How many cells survived. */
    public int visibleCount() {
        return ids.size();
    }

    /** How many cells the culler knew about at all. */
    public int total() {
        return total;
    }

    /** The fraction of the world the eye is spared; 1.0 means the cull did nothing. */
    public double cullRatio() {
        return total == 0 ? 0.0 : 1.0 - ((double) ids.size() / (double) total);
    }

    @Override
    public String toString() {
        return "PVS{" + ids.size() + "/" + total + "}";
    }
}
