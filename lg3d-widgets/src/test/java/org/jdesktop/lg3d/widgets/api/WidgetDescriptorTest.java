/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.widgets.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WidgetDescriptor}, the immutable widget-type metadata + factory
 * value object. Pure data, no Java 3D and no Swing, so it runs headless. The
 * {@link Widget} used to check {@link WidgetDescriptor#create()} is a stub whose
 * scene-graph accessors return null: {@code create()} only has to hand back
 * whatever the factory produced, it never touches the node.
 */
class WidgetDescriptorTest {

    /** Minimal {@link Widget} that never builds a peer or a scene-graph node. */
    private static final class StubWidget implements Widget {
        @Override public String id() { return "stub"; }
        @Override public String displayName() { return "Stub"; }
        @Override public void init(WidgetContext context) { }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void dispose() { }
        @Override public Component3D node() { return null; }
        @Override public Vector3f getPreferredSize() { return null; }
    }

    @Test
    @DisplayName("a fully-specified descriptor keeps every field verbatim")
    void keepsAllFields() {
        WidgetDescriptor d = new WidgetDescriptor("clock", "Clock", "System",
                "/resources/images/icon/clock.png", 100, 90, StubWidget::new);
        assertEquals("clock", d.id());
        assertEquals("Clock", d.displayName());
        assertEquals("System", d.category());
        assertEquals("/resources/images/icon/clock.png", d.iconResource());
        assertEquals(100, d.defaultWidth());
        assertEquals(90, d.defaultHeight());
    }

    @Test
    @DisplayName("blank name/category fall back to the id / General")
    void appliesDefaults() {
        WidgetDescriptor d = new WidgetDescriptor("x", "  ", null, null, 0, -5,
                StubWidget::new);
        assertEquals("x", d.displayName(), "blank name falls back to the id");
        assertEquals("General", d.category(), "blank category falls back to General");
        assertEquals(120, d.defaultWidth(), "non-positive width falls back to 120");
        assertEquals(80, d.defaultHeight(), "non-positive height falls back to 80");
        assertNull(d.iconResource(), "a null icon stays null");
    }

    @Test
    @DisplayName("a zero width/height is non-positive and falls back to 120/80")
    void appliesDefaultsAtTheBoundary() {
        // Exactly 0 sits on the `> 0` boundary for both dimensions, so a
        // descriptor built with 0/0 must fall back to the 120x80 defaults.
        WidgetDescriptor d = new WidgetDescriptor("x", "n", "c", null, 0, 0,
                StubWidget::new);
        assertEquals(120, d.defaultWidth());
        assertEquals(80, d.defaultHeight());
    }

    @Test
    @DisplayName("a null/blank id or a null factory is rejected")
    void guardsArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new WidgetDescriptor(null, "n", "c", null, 1, 1, StubWidget::new));
        assertThrows(IllegalArgumentException.class,
                () -> new WidgetDescriptor("  ", "n", "c", null, 1, 1, StubWidget::new));
        assertThrows(IllegalArgumentException.class,
                () -> new WidgetDescriptor("x", "n", "c", null, 1, 1, null));
    }

    @Test
    @DisplayName("create() returns a fresh instance from the factory")
    void createUsesFactory() {
        WidgetDescriptor d = new WidgetDescriptor("x", "n", "c", null, 1, 1,
                StubWidget::new);
        Widget a = d.create();
        Widget b = d.create();
        assertEquals("stub", a.id());
        // The supplier builds a new object each call, so the two are distinct.
        assertNotSame(a, b);
    }

    @Test
    @DisplayName("create() hands back the exact instance a singleton factory gives")
    void createReturnsFactoryResult() {
        StubWidget only = new StubWidget();
        WidgetDescriptor d = new WidgetDescriptor("x", "n", "c", null, 1, 1,
                () -> only);
        assertSame(only, d.create());
    }

    @Test
    @DisplayName("toString reads 'name [id]'")
    void toStringFormat() {
        WidgetDescriptor d = new WidgetDescriptor("clock", "Clock", "System",
                null, 1, 1, StubWidget::new);
        assertEquals("Clock [clock]", d.toString());
    }
}
