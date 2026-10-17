/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.presentoire.transition;

import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Vector3f;

/**
 * A transition that spins the slide container a full turn about the Y axis,
 * swapping the slide at the half-way point so it appears to flip over.
 *
 * <p>See {@link Transition} for why this animation only runs when
 * {@link #runTransition} is called directly. The debug trace is gone and an
 * interrupted spin now restores the interrupt flag and settles on the final
 * angle rather than printing a stack trace.</p>
 *
 * @author Pierre Ducroquet
 */
public class RotationTransition extends Transition {

    /**
     * Rotates {@code comp} from angle {@code from} to angle {@code to} over about
     * {@code duration} milliseconds.
     *
     * @param comp     the component to rotate
     * @param from     the start angle
     * @param to       the destination angle
     * @param duration the duration of the rotation
     */
    public void rotate(Component3D comp, float from, float to, long duration) {
        float angle = from;
        float diff = (to - from) / duration;
        while (angle < to) {
            comp.changeRotationAngle(angle);
            angle = angle + diff;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        comp.changeRotationAngle(to);
    }

    /** Runs the rotation transition. */
    public void runTransition(Component3D mainComponent,
            Component3D slideContainer, Component3D nextSlide) {
        slideContainer.changeRotationAxis(new Vector3f(0.0f, 1.0f, 0.0f));
        slideContainer.changeRotationAngle(0.0f);
        rotate(slideContainer, 0.0f, 3.14f, 100);
        slideContainer.removeAllChildren();
        slideContainer.addChild(nextSlide);
        rotate(slideContainer, 3.14f, 6.28f, 100);
        slideContainer.changeRotationAngle(0.0f);
    }
}
