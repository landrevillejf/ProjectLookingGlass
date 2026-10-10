/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Component;
import java.awt.Graphics;
import javax.swing.Icon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the Phase-6 additive {@link Icon} support on
 * {@link ToolbarContribution}: the new six-argument constructor carries the
 * icon through {@link ToolbarContribution#getIcon()}, and the legacy
 * four- / five-argument constructors keep compiling and report a {@code null}
 * icon, so third-party extensions are unaffected.
 */
class ToolbarContributionTest {

    private static final Icon SAMPLE = new Icon() {
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { }
    };

    @Test
    @DisplayName("the legacy constructors default to a null icon")
    void legacyConstructorsNullIcon() {
        ToolbarContribution four = new ToolbarContribution("a", "A", "tip", () -> { });
        ToolbarContribution five = new ToolbarContribution("b", "B", "tip", () -> { }, "control alt A");
        assertNull(four.getIcon());
        assertNull(five.getIcon());
        assertEquals("control alt A", five.getAccelerator());
        assertEquals("", four.getAccelerator());
    }

    @Test
    @DisplayName("the six-argument constructor carries the icon")
    void iconConstructor() {
        ToolbarContribution c = new ToolbarContribution(
                "c", "C", "tip", () -> { }, "control alt C", SAMPLE);
        assertNotNull(c.getIcon());
        assertEquals(SAMPLE, c.getIcon());
        assertEquals("control alt C", c.getAccelerator());
    }

    @Test
    @DisplayName("an explicit null icon is allowed and equality stays keyed on id")
    void equalityOnId() {
        ToolbarContribution withIcon = new ToolbarContribution(
                "id", "One", "t", () -> { }, null, SAMPLE);
        ToolbarContribution withoutIcon = new ToolbarContribution(
                "id", "Two", "t", () -> { });
        assertNull(new ToolbarContribution("x", "X", "t", () -> { }, null, null).getIcon());
        assertEquals(withIcon, withoutIcon, "same id => equal, icon ignored");
        assertEquals(withIcon.hashCode(), withoutIcon.hashCode());
    }
}
