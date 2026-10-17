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
package org.jdesktop.lg3d.apps.presentoire.model;

import java.util.Collections;
import java.util.List;

/**
 * One parsed slide: an id, the optional name of the transition that reveals it,
 * and its ordered list of {@link SlideElement}s.
 *
 * <p>Immutable and AWT-free, so a whole deck can be parsed and asserted on in a
 * headless test without a display or a scene graph. The {@code transition} is
 * the raw attribute value - a built-in name ({@code rotation}, {@code scale},
 * {@code transparency}), a script-transition id, or a {@code .class} path baked
 * into the document - and an empty string when the slide declares none; the 3D
 * {@code TransitionFactory} resolves it, while the 2D panel simply cross-fades.</p>
 */
public final class SlideModel {

    private final String id;
    private final String transition;
    private final List<SlideElement> elements;

    public SlideModel(String id, String transition, List<SlideElement> elements) {
        this.id = (id == null) ? "" : id;
        this.transition = (transition == null) ? "" : transition;
        this.elements = Collections.unmodifiableList(elements);
    }

    /** The slide's {@code id} attribute, never null. */
    public String id() {
        return id;
    }

    /** The transition name that reveals this slide; empty when none is declared. */
    public String transition() {
        return transition;
    }

    /** True when this slide declares a transition name. */
    public boolean hasTransition() {
        return !transition.isEmpty();
    }

    /** The immutable, document-ordered elements of this slide. */
    public List<SlideElement> elements() {
        return elements;
    }
}
