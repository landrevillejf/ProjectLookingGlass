/*
 * In-tree reimplementation of the j3d-contrib-utils TransparencyOrderedGroup,
 * ported to the Jogamp Java 3D 1.7 API (org.jogamp.java3d). See the traverser
 * package for the full rationale (the original binary was compiled against the
 * legacy javax.media.j3d packages).
 *
 * Semantics (per the LG3D wrapper documentation): transparent shapes in the
 * first child of this group render behind those in the second child, and so on.
 * The childIndexOrder array maps render position -> child index.
 *
 * NOTE: this is a best-effort reimplementation. The original relied on a
 * renderer hook to honour childIndexOrder at draw time; here the order is
 * realised by physically arranging the group's children while the graph is
 * still being constructed (not live/compiled). Within a single child, distance
 * based sorting is provided natively by Java 3D via the transparency sorting
 * policy and the comparator installed by TransparencyOrderController.
 */
package org.jdesktop.j3d.utils.scenegraph.transparency;

import org.jogamp.java3d.Group;
import org.jogamp.java3d.Node;

/**
 * A {@link Group} that gives explicit control over the rendering order of the
 * transparent objects in its child subgraphs.
 */
public class TransparencyOrderedGroup extends Group {

    private int[] childIndexOrder = null;

    public TransparencyOrderedGroup() {
        super();
    }

    /**
     * Sets the child index order array. A null array means children render in
     * increasing index order. Otherwise the array must be a permutation of
     * {@code [0, numChildren-1]}.
     *
     * @throws IllegalArgumentException if the array is non-null and is not a
     *         valid permutation of the child indices
     */
    public void setChildIndexOrder(int[] childIndexOrder) {
        if (childIndexOrder == null) {
            this.childIndexOrder = null;
            return;
        }

        int n = numChildren();
        if (childIndexOrder.length != n)
            throw new IllegalArgumentException(
                "childIndexOrder.length (" + childIndexOrder.length
                + ") != numChildren (" + n + ")");

        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            int idx = childIndexOrder[i];
            if (idx < 0 || idx >= n || seen[idx])
                throw new IllegalArgumentException(
                    "invalid or duplicate childIndexOrder entry: " + idx);
            seen[idx] = true;
        }

        this.childIndexOrder = childIndexOrder.clone();
        applyOrder();
    }

    /**
     * @return a copy of the child index order array, or null if children render
     *         in increasing index order.
     */
    public int[] getChildIndexOrder() {
        return childIndexOrder == null ? null : childIndexOrder.clone();
    }

    /**
     * Appends {@code child} and then applies the given child index order.
     */
    public void addChild(Node child, int[] childIndexOrder) {
        super.addChild(child);
        setChildIndexOrder(childIndexOrder);
    }

    /**
     * Physically arranges children so that draw order follows childIndexOrder.
     * Only performed while the graph is still under construction; reordering a
     * live/compiled graph would require write capabilities and can disrupt the
     * running scene, so it is skipped in that case.
     */
    private void applyOrder() {
        if (childIndexOrder == null)
            return;
        if (isLive() || isCompiled())
            return;

        int n = numChildren();
        Node[] kids = new Node[n];
        for (int i = 0; i < n; i++)
            kids[i] = getChild(i);

        for (int i = n - 1; i >= 0; i--)
            removeChild(i);

        for (int pos = 0; pos < n; pos++)
            addChild(kids[childIndexOrder[pos]]);
    }
}
