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

/**
 * Builds the native-3D {@link Element} renderer for a parsed
 * {@link SlideElement}.
 *
 * <p>The original switched on the DOM node name and logged every branch to
 * {@code System.out}; this switches on the model type with a Java 21
 * pattern-matching {@code instanceof} and stays quiet. It returns null only for a
 * model type it does not know (which cannot happen today, since the parser only
 * emits the three subclasses), and {@link org.jdesktop.lg3d.apps.presentoire.Slide}
 * null-guards the result.</p>
 *
 * @author Pierre Ducroquet
 */
public final class ElementFactory {

    private ElementFactory() {
        // no instances
    }

    /**
     * Returns the renderer for {@code model}, or null if its kind is unknown.
     *
     * @param model    the parsed slide element
     * @param document the owning deck, used to resolve images
     */
    public static Element getElement(SlideElement model, PresentoireModel document) {
        if (model instanceof SlideElement.Text text) {
            return new TextElement(text, document);
        } else if (model instanceof SlideElement.Image image) {
            return new ImageElement(image, document);
        } else if (model instanceof SlideElement.ListElement list) {
            return new ListElement(list, document);
        }
        return null;
    }
}
