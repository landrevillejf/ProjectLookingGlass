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
 * The base of every transition: it runs the slide swap on its own thread so a
 * transition cannot freeze the rest of Presentoire.
 *
 * <p>The default transition is a no-animation swap - remove the current slide,
 * add the next. Subclasses may declare a richer {@code runTransition}, but note
 * the inherited quirk this port preserves: {@link #runTransition} is
 * {@code private}, so {@link TransitionThread#run()} binds to it non-virtually and
 * a subclass animation only executes if that subclass's method is called directly.
 * Keeping the reliable swap is deliberate - a resurrection whose 3D animation
 * cannot be verified without a live desktop should not start running unverified
 * scene-graph mutations that could leave a slide stuck mid-transition.</p>
 *
 * @author Pierre Ducroquet
 */
public class Transition {

    /**
     * Really performs the transition; run by {@link TransitionThread}. Here it is
     * the plain swap. Should never be called directly.
     *
     * @param mainComponent  the main Presentoire component
     * @param slideContainer the container holding the current slide
     * @param nextSlide      the next slide to reveal
     */
    private void runTransition(Component3D mainComponent, Component3D slideContainer,
            Component3D nextSlide) {
        slideContainer.removeAllChildren();
        slideContainer.addChild(nextSlide);
    }

    /**
     * Launches the transition thread.
     *
     * @param mainComponent  the main Presentoire component
     * @param slideContainer the container holding the current slide
     * @param nextSlide      the next slide to reveal
     */
    public void executeTransition(Component3D mainComponent, Component3D slideContainer,
            Component3D nextSlide) {
        new TransitionThread(mainComponent, slideContainer, nextSlide).start();
    }

    /**
     * The thread a transition runs on.
     *
     * @author Pierre Ducroquet
     */
    private class TransitionThread extends Thread {

        private final Component3D mainComponent;
        private final Component3D slideContainer;
        private final Component3D nextSlide;

        TransitionThread(Component3D mainComponent, Component3D slideContainer,
                Component3D nextSlide) {
            this.mainComponent = mainComponent;
            this.slideContainer = slideContainer;
            this.nextSlide = nextSlide;
        }

        @Override
        public void run() {
            runTransition(mainComponent, slideContainer, nextSlide);
        }
    }
}
