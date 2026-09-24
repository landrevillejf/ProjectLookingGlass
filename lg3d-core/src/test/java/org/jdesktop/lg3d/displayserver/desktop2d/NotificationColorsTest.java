/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link NotificationColors}: each severity maps to its accent, a null
 * kind falls back to the info colour, and the three accents are distinct and
 * opaque.
 */
class NotificationColorsTest {

    @Test
    @DisplayName("each severity maps to its own accent colour")
    void mapsEachKind() {
        assertSame(NotificationColors.INFO,
                NotificationColors.accentFor(Notification.Kind.INFO));
        assertSame(NotificationColors.WARNING,
                NotificationColors.accentFor(Notification.Kind.WARNING));
        assertSame(NotificationColors.ERROR,
                NotificationColors.accentFor(Notification.Kind.ERROR));
    }

    @Test
    @DisplayName("a null kind falls back to the info colour")
    void nullIsInfo() {
        assertSame(NotificationColors.INFO, NotificationColors.accentFor(null));
    }

    @Test
    @DisplayName("the three accents are distinct and fully opaque")
    void colorsAreDistinctAndOpaque() {
        assertNotEquals(NotificationColors.INFO.getRGB(),
                NotificationColors.WARNING.getRGB());
        assertNotEquals(NotificationColors.WARNING.getRGB(),
                NotificationColors.ERROR.getRGB());
        assertEquals(255, NotificationColors.ERROR.getAlpha());
    }
}
