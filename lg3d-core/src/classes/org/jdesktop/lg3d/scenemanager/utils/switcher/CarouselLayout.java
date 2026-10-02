/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.switcher;

/**
 * The pure positioning mathematics of the 3D window carousel, lifted out of
 * the scene graph so it is headless-testable. It reproduces the circular
 * fan-out of the {@code CDViewer} demo's {@code CDLayout}: windows are placed
 * on a circle in the XY plane, one per slot, starting at the front and going
 * counter-clockwise, each turned tangentially so the front card faces the
 * viewer. The hosting {@link WindowCarousel3D} tilts the whole circle (the
 * {@code bodyAngle} X-rotation of {@code CDViewer}'s container) and consumes the
 * {@link Pose}s this class returns.
 *
 * <p>No Java 3D types appear here on purpose: {@code org.jogamp.java3d} objects
 * cannot be constructed in the headless unit-test JVM, so every float this
 * returns is computed from plain arguments and asserted directly.</p>
 */
public final class CarouselLayout {

    /**
     * Angle of the front slot on the circle. {@code 3*PI/2} (270 degrees) puts
     * the front card at the bottom of the untilted circle, exactly where
     * {@code CDViewer} seats its front CD before the container tilt lifts it
     * toward the viewer.
     */
    static final float FRONT_RAD = (float) (Math.PI * 3 / 2);

    private static final float TWO_PI = (float) (Math.PI * 2);

    /** Default per-card depth offset, so rear cards stack behind the front. */
    static final float DEFAULT_STACK_SPACING = 0.00125f;
    /** Default scale of the front card while the carousel is focused. */
    static final float DEFAULT_FRONT_SCALE = 1.2f;
    /** Default toward-viewer push of the front card while focused. */
    static final float DEFAULT_FRONT_Z_PUSH = 0.02f;

    private final float stackSpacing;
    private final float frontScale;
    private final float frontZPush;

    /** Builds a layout with the default stack spacing, front scale and push. */
    public CarouselLayout() {
        this(DEFAULT_STACK_SPACING, DEFAULT_FRONT_SCALE, DEFAULT_FRONT_Z_PUSH);
    }

    /**
     * Builds a layout with explicit tuning constants (used by tests to assert
     * the arithmetic without hard-coding the defaults).
     */
    public CarouselLayout(float stackSpacing, float frontScale, float frontZPush) {
        this.stackSpacing = stackSpacing;
        this.frontScale = frontScale;
        this.frontZPush = frontZPush;
    }

    /**
     * The pose of one card: a translation, the tangential rotation about Z that
     * turns it to face outward from the circle, a uniform scale, and whether it
     * is the front (selected) card.
     */
    public record Pose(float x, float y, float z, float rotAngle, float scale,
            boolean front) {
    }

    /** A neutral pose for an empty carousel; the caller hides it anyway. */
    public static Pose emptyPose() {
        return new Pose(0f, 0f, 0f, 0f, 1f, false);
    }

    /**
     * Computes the pose of the card at list index {@code slot} for a carousel of
     * {@code count} cards whose front card is list index {@code frontIndex}.
     *
     * @param slot      the card's index in the (MRU-ordered) window list
     * @param frontIndex the index currently seated at the front
     * @param count     how many cards the carousel holds
     * @param fanRadius the circle radius; a focused carousel may widen it
     * @param focused   whether the carousel is fanned out (pointer over it)
     */
    public Pose pose(int slot, int frontIndex, int count, float fanRadius,
            boolean focused) {
        if (count <= 0) {
            return emptyPose();
        }
        int position = positionOf(slot, frontIndex, count);
        float r = FRONT_RAD + TWO_PI / count * position;
        float x = fanRadius * (float) Math.cos(r);
        float y = fanRadius * (float) Math.sin(r);
        float rotAngle = r - FRONT_RAD;
        boolean front = position == 0;

        float z = stackDepth(position, count);
        float scale = 1.0f;
        if (front && focused) {
            z += frontZPush;
            scale = frontScale;
        }
        return new Pose(x, y, z, rotAngle, scale, front);
    }

    /**
     * The circular position (0 == front) of list index {@code slot}, wrapping so
     * it is always in {@code [0, count)}. Exposed for tests and for the node's
     * click-to-front mapping.
     */
    public static int positionOf(int slot, int frontIndex, int count) {
        if (count <= 0) {
            return 0;
        }
        return ((slot - frontIndex) % count + count) % count;
    }

    /**
     * The receding depth of a card at circular {@code position}: cards behind the
     * front step back one spacing at a time, mirroring {@code CDViewer}'s stack
     * so overlapping cards sort correctly.
     */
    private float stackDepth(int position, int count) {
        int half = count / 2;
        if (position > half) {
            return stackSpacing * (position - half);
        }
        if (position == half) {
            return -stackSpacing * 0.5f;
        }
        return -stackSpacing * (position + 1);
    }
}
