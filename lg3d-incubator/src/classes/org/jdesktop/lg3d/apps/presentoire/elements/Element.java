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
package org.jdesktop.lg3d.apps.presentoire.elements;

import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.wg.Component3D;

/**
 * The base of the native-3D slide-element renderers: it turns one parsed
 * {@link SlideElement} into a {@code Component3D}.
 *
 * <p>The 2006 original both parsed the XML attribute map <em>and</em> built the
 * scene graph in each {@code drawElement()}, so parsing could not be reused or
 * tested without a live universe. This port reads geometry from the shared
 * AWT-free model ({@link SlideElement}) and only constructs Java 3D nodes here;
 * the same model feeds the plain-Swing 2D panel. A renderer never returns null -
 * an element it cannot draw (a missing image, say) yields its empty base
 * {@code Component3D}, so {@link org.jdesktop.lg3d.apps.presentoire.Slide} can
 * add every child unconditionally without a null check.</p>
 *
 * @author Pierre Ducroquet
 */
public abstract class Element {

    /** The parsed element this renderer draws. */
    protected final SlideElement model;

    /** The owning document, used to resolve slide images from the deck archive. */
    protected final PresentoireModel document;

    protected Element(SlideElement model, PresentoireModel document) {
        this.model = model;
        this.document = document;
    }

    /**
     * Builds the positioned base node: a {@code Component3D} translated to the
     * element's {@code (x, y, z)} in slide units, exactly as the original did
     * ({@code z} defaults to {@link SlideElement#DEFAULT_Z} to keep stacked
     * coplanar quads from z-fighting).
     *
     * @return the translated base component, never null
     */
    protected Component3D getBaseComponent() {
        Component3D result = new Component3D();
        result.setTranslation(model.x(), model.y(), model.z());
        return result;
    }

    /**
     * Renders this element into the scene graph.
     *
     * @return the element's {@code Component3D}, never null
     */
    public abstract Component3D drawElement();
}
