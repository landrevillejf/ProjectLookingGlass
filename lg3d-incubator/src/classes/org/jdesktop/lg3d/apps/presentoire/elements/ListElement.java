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

import java.util.List;
import org.jdesktop.lg3d.apps.presentoire.Colors;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.utils.shape.Sphere;
import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Color4f;

/**
 * The native-3D renderer of a {@code <ul>} bullet list.
 *
 * <p>Each {@code <li>} becomes a text quad with a small translucent sphere bullet
 * to its left, stacked downward - the same layout and the same
 * {@code height/count} line pitch the 2006 original used, now driven by the
 * parsed {@link SlideElement.ListElement} items rather than a second walk of the
 * DOM. An empty list yields the empty base component (the original returned
 * {@code null}), and the "Ajout de la sphere" debug print is gone.</p>
 *
 * @author Pierre Ducroquet
 */
public class ListElement extends Element {

    private final SlideElement.ListElement list;

    public ListElement(SlideElement.ListElement list, PresentoireModel document) {
        super(list, document);
        this.list = list;
    }

    @Override
    public Component3D drawElement() {
        Component3D result = getBaseComponent();
        List<String> items = list.items();
        int count = items.size();
        if (count == 0) {
            return result;
        }
        float width = list.width();
        float height = list.height();
        Color4f colorVector = Colors.getColor(list.argb());
        for (int idx = 0; idx < count; idx++) {
            GlassyText2D txt2D =
                    new GlassyText2D(items.get(idx), width, height / count, colorVector);
            Component3D lineCmp = new Component3D();
            Component3D sphereCmp = new Component3D();
            Component3D textCmp = new Component3D();

            sphereCmp.addChild(new Sphere(height / (4 * count), Sphere.GENERATE_NORMALS, 10,
                    new SimpleAppearance(colorVector.x, colorVector.y, colorVector.z, 0.5f)));
            textCmp.addChild(txt2D);
            sphereCmp.setTranslation(-height / (4 * count), height / (2 * count), 0.0f);
            textCmp.setTranslation(0.0f, 0.0f, 0.0f);
            lineCmp.addChild(textCmp);
            lineCmp.addChild(sphereCmp);
            lineCmp.setTranslation(height / count, -height / count * idx, 0.0f);
            result.addChild(lineCmp);
        }
        return result;
    }
}
