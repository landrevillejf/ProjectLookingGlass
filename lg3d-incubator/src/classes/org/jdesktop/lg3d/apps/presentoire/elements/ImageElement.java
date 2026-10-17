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

import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.sg.Texture;
import org.jdesktop.lg3d.sg.utils.image.TextureLoader;
import org.jdesktop.lg3d.utils.shape.ImagePanel;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;

/**
 * The native-3D renderer of an {@code <img>} element.
 *
 * <p>The image bytes are resolved through the shared model
 * ({@link PresentoireModel#loadImage(String)}), which decodes them headlessly from
 * the deck archive and returns null - never throws - when the picture is missing
 * or unreadable. Where the 2006 original returned {@code null} on an
 * {@link java.io.IOException} (forcing every caller to null-check, and dropping
 * the rest of the slide with it), this renderer returns its empty base
 * {@link Component3D} so one broken image cannot take down a slide.</p>
 *
 * @author Pierre Ducroquet
 */
public class ImageElement extends Element {

    private final SlideElement.Image image;

    public ImageElement(SlideElement.Image image, PresentoireModel document) {
        super(image, document);
        this.image = image;
    }

    @Override
    public Component3D drawElement() {
        Component3D result = getBaseComponent();
        BufferedImage picture = document.loadImage(image.src());
        if (picture == null) {
            // No archive (the demo deck), a missing entry, or undecodable bytes:
            // leave the positioned base component empty rather than return null.
            return result;
        }
        ImagePanel img = new ImagePanel(image.width(), image.height());
        Texture texture = new TextureLoader(picture).getTexture();
        SimpleAppearance app = new SimpleAppearance(0.0f, 0.0f, 0.0f);
        app.setTexture(texture);
        img.setAppearance(app);
        result.addChild(img);
        return result;
    }
}
