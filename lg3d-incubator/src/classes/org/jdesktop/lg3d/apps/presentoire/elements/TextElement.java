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

import org.jdesktop.lg3d.apps.presentoire.Colors;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Color4f;

/**
 * The native-3D renderer of a {@code <text>} block.
 *
 * <p>It reads geometry and colour from the shared AWT-free
 * {@link SlideElement.Text} model instead of re-parsing the XML attribute map, so
 * the plain-Swing panel paints exactly the same text from the same data. A
 * single-line block becomes one {@link GlassyText2D} quad; a multi-line block
 * (the content contains {@code \n}) becomes one quad per line stacked downward,
 * exactly as the 2006 original laid it out. The debug {@code System.out.println}
 * per line is gone.</p>
 *
 * @author Pierre Ducroquet
 */
public class TextElement extends Element {

    private final SlideElement.Text text;

    public TextElement(SlideElement.Text text, PresentoireModel document) {
        super(text, document);
        this.text = text;
    }

    @Override
    public Component3D drawElement() {
        Component3D result = getBaseComponent();
        float width = text.width();
        float height = text.height();
        Color4f colorVector = Colors.getColor(text.argb());
        String[] lines = text.lines();
        if (lines.length > 1) {
            // Several lines: one text quad per line, stacked downward.
            Component3D multiline = new Component3D();
            int count = lines.length;
            for (int idx = 0; idx < count; idx++) {
                GlassyText2D txt2D =
                        new GlassyText2D(lines[idx], width, height / count, colorVector);
                Component3D lineCmp = new Component3D();
                lineCmp.addChild(txt2D);
                lineCmp.setTranslation(0.0f, -height / count * idx, 0.0f);
                multiline.addChild(lineCmp);
            }
            result.addChild(multiline);
        } else {
            result.addChild(new GlassyText2D(text.text(), width, height, colorVector));
        }
        return result;
    }
}
