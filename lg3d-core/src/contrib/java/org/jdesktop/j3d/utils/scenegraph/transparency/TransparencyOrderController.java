/*
 * In-tree reimplementation of the j3d-contrib-utils TransparencyOrderController,
 * ported to the Jogamp Java 3D 1.7 API (org.jogamp.java3d). See the traverser
 * package for the full rationale.
 *
 * The original contrib controller was a Behavior that re-sorted transparent
 * geometry by distance from the viewer every frame. Modern Java 3D provides
 * this natively: a transparency sorting policy on the View plus a comparator
 * registered with TransparencySortController. This reimplementation therefore
 * installs a SimpleDistanceComparator on the supplied View, which yields the
 * same back-to-front ordering of transparent geometry without bespoke per-frame
 * scene-graph manipulation.
 */
package org.jdesktop.j3d.utils.scenegraph.transparency;

import java.util.Iterator;

import org.jogamp.java3d.Behavior;
import org.jogamp.java3d.BoundingSphere;
import org.jogamp.java3d.View;
import org.jogamp.java3d.WakeupCriterion;
import org.jogamp.java3d.utils.scenegraph.transparency.SimpleDistanceComparator;
import org.jogamp.java3d.utils.scenegraph.transparency.TransparencySortController;
import org.jogamp.vecmath.Point3d;

/**
 * Enables distance-based transparency sorting for the given {@link View}.
 */
public class TransparencyOrderController extends Behavior {

    private final View view;

    public TransparencyOrderController(View view) {
        this.view = view;
        setSchedulingBounds(new BoundingSphere(new Point3d(), 1.0E12));
        if (view != null)
            TransparencySortController.setComparator(view,
                new SimpleDistanceComparator());
    }

    @Override
    public void initialize() {
        // Sorting is handled natively by the renderer once the comparator and
        // the View's transparency sorting policy are set; no per-frame stimulus
        // processing is required. Re-assert the comparator in case it was
        // installed before the view was fully realised.
        if (view != null)
            TransparencySortController.setComparator(view,
                new SimpleDistanceComparator());
    }

    @Override
    public void processStimulus(Iterator<WakeupCriterion> criteria) {
        // No-op: transparency ordering is delegated to the Java 3D renderer.
    }
}
