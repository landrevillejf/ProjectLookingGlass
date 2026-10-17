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
package org.jdesktop.lg3d.apps.presentoire;

import org.jdesktop.lg3d.apps.presentoire.elements.Element;
import org.jdesktop.lg3d.apps.presentoire.elements.ElementFactory;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;
import org.jdesktop.lg3d.wg.Component3D;

/**
 * The native-3D view of one parsed slide: it renders a {@link SlideModel} into a
 * {@code Component3D}.
 *
 * <p>The 2006 original held the raw DOM {@code Node} and re-walked it on every
 * {@link #drawPage()}; this holds the AWT-free {@link SlideModel} the shared
 * parser produced and only builds scene-graph nodes, so the same slide data also
 * drives the plain-Swing 2D panel. Element rendering is delegated to
 * {@link ElementFactory}, and a renderer that yields null (an unknown element
 * kind) is skipped rather than added.</p>
 *
 * @author Pierre Ducroquet
 */
public class Slide {

    private final SlideModel model;
    private final PresentoireModel document;

    /**
     * @param model    the parsed slide
     * @param document the owning deck, used to resolve slide images
     */
    public Slide(SlideModel model, PresentoireModel document) {
        this.model = model;
        this.document = document;
    }

    /** The slide's {@code id} attribute, never null. */
    public String getId() {
        return model.id();
    }

    /** The parsed slide data this view renders. */
    public SlideModel getModel() {
        return model;
    }

    /** The name of the transition that reveals this slide; empty when none. */
    public String getTransition() {
        return model.transition();
    }

    /** True when this slide declares a transition name. */
    public boolean hasTransition() {
        return model.hasTransition();
    }

    /**
     * Draws the slide: creates a renderer for each element and adds its
     * {@code Component3D} to the page.
     *
     * @return a {@code Component3D} containing the whole slide, never null
     */
    public Component3D drawPage() {
        Component3D result = new Component3D();
        for (SlideElement elementModel : model.elements()) {
            Element element = ElementFactory.getElement(elementModel, document);
            if (element == null) {
                continue;
            }
            Component3D drawn = element.drawElement();
            if (drawn != null) {
                result.addChild(drawn);
            }
        }
        return result;
    }
}
