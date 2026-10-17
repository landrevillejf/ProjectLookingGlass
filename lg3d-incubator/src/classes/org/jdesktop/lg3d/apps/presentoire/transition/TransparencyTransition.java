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

/**
 * A transition that fades the slide container out, swaps the slide, then fades it
 * back in.
 *
 * <p>See {@link Transition} for why this animation only runs when
 * {@link #runTransition} is called directly. An interrupted fade now restores the
 * interrupt flag and settles on the final opacity rather than printing a stack
 * trace.</p>
 *
 * @author Pierre Ducroquet
 */
public class TransparencyTransition extends Transition {

    /**
     * Fades {@code comp} from opacity {@code from} to opacity {@code to} over
     * about {@code duration} milliseconds.
     *
     * @param comp     the component to fade
     * @param from     the start opacity
     * @param to       the destination opacity
     * @param duration the duration of the fade
     */
    public void transparency(Component3D comp, float from, float to, long duration) {
        float opac = from;
        float diff = (to - from) / duration;
        int factor = (diff < 0) ? -1 : 1;
        while (factor * opac < to) {
            comp.changeTransparency(opac);
            opac = opac + diff;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        comp.changeTransparency(to);
    }

    /** Runs the transparency transition. */
    public void runTransition(Component3D mainComponent,
            Component3D slideContainer, Component3D nextSlide) {
        transparency(slideContainer, 0.0f, 1.0f, 100);
        slideContainer.removeAllChildren();
        slideContainer.addChild(nextSlide);
        transparency(slideContainer, 1.0f, 0.0f, 100);
    }
}
