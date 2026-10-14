/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: one name tag, aimed at whoever reads it.
 *
 * A tag is a flat card, and a flat card seen edge-on is a line of pixels: the
 * world's names would vanish the moment the walker turned. The 2006 client solved
 * this with its own billboarded text; Java 3D has no billboard node, and a
 * {@code Behavior} that hunts the eye every frame needs a view-platform transform
 * this fork will not let it read (there is no {@code getTransform} on
 * {@code ViewPlatform}, and the parent read it would need is a capability fight).
 * So the aim is pulled, not polled: the viewer already writes the eye in exactly
 * one place, {@code WorldViewer.apply}, and it hands that eye to the scene, which
 * turns each card to meet it. The camera only ever moves from there, so a card
 * aimed at the eye it was written with is a card aimed at the eye.
 *
 * The card's spinner carries the rotation alone; the translation that seats the
 * tag in the world lives on the group above it, so a tag never drifts from its
 * cell however the eye moves.
 */
package org.jdesktop.wonderland.renderer.view;

import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;
import org.jogamp.vecmath.AxisAngle4d;
import org.jogamp.vecmath.Point3d;

/** Yaws one tag about the vertical axis until its face points at the eye. */
final class NameTag {

    private final TransformGroup spin;
    private final Point3d anchor;

    NameTag(TransformGroup spin, Point3d anchor) {
        this.spin = spin;
        this.anchor = new Point3d(anchor);
    }

    /**
     * Turn the card toward {@code eye}.
     *
     * <p>Nothing here may throw. The scene is written from the EDT while a window
     * is being shown, and a tag that cannot be aimed is a name nobody reads -- not
     * a world that fails to appear. A transform group that rejects the write
     * (compiled without the bit, or already detached) is swallowed for that
     * reason.</p>
     */
    void aim(Point3d eye) {
        if (eye == null) {
            return;
        }
        try {
            Transform3D turn = new Transform3D();
            turn.setRotation(new AxisAngle4d(0, 1, 0, yawToward(eye, anchor)));
            spin.setTransform(turn);
        } catch (RuntimeException | Error ignored) {
            // see the header: a card that cannot turn keeps the way it was authored
        }
    }

    /**
     * The yaw about +Y that points a card's +Z normal at the eye.
     *
     * <p>Horizontal only, on purpose: a tag that pitched to meet an eye above or
     * below it would stop being readable text and become a foreshortened smudge.
     * The 2009 tags stand upright in the world and only turn.</p>
     */
    static double yawToward(Point3d eye, Point3d anchor) {
        double dx = eye.x - anchor.x;
        double dz = eye.z - anchor.z;
        if (dx == 0.0 && dz == 0.0) {
            return 0.0;
        }
        return Math.atan2(dx, dz);
    }
}
