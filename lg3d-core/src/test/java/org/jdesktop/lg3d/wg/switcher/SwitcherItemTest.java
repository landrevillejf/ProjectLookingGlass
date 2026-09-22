/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.wg.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the {@link SwitcherItem} value object. Pure data; runs headless. */
class SwitcherItemTest {

    @Test
    @DisplayName("accessors return what was supplied; a null name becomes empty")
    void accessors() {
        Object window = new Object();
        Icon icon = new Icon() {
            @Override
            public int getIconWidth() {
                return 8;
            }

            @Override
            public int getIconHeight() {
                return 8;
            }

            @Override
            public void paintIcon(java.awt.Component c, java.awt.Graphics g,
                                  int x, int y) {
                // no-op
            }
        };
        Image thumb = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);

        SwitcherItem full = new SwitcherItem(window, "Firefox", icon, thumb);
        assertSame(window, full.getWindow());
        assertEquals("Firefox", full.getName());
        assertSame(icon, full.getIcon());
        assertSame(thumb, full.getThumbnail());
        assertEquals("Firefox", full.toString());

        SwitcherItem noThumb = new SwitcherItem(window, "Term", icon);
        assertNull(noThumb.getThumbnail(), "the 3-arg form has no thumbnail");

        SwitcherItem nullName = new SwitcherItem(window, null, null);
        assertEquals("", nullName.getName(), "a null name is normalised to empty");
        assertNull(nullName.getIcon());
    }
}
