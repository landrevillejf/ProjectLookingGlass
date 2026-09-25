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
package org.jdesktop.lg3d.widgets.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.widgets.api.WidgetDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link BuiltinWidgetProvider#descriptors()}, which pairs each entry of
 * the Java 3D-free {@link BuiltinWidgetCards} catalogue with the 3D widget that
 * hosts it. Building the descriptor list touches no scene graph, so it runs
 * headless; the descriptors' {@code create()} factories (which would build a
 * {@code Component3D}) are deliberately left unexercised here.
 */
class BuiltinWidgetProviderTest {

    private static final List<String> IDS =
            List.of("clock", "calendar", "temperature", "cpu", "memory", "indicators", "weather");

    @Test
    @DisplayName("descriptors() lists the seven built-ins in catalogue order")
    void listsAllBuiltins() {
        List<WidgetDescriptor> out = new BuiltinWidgetProvider().descriptors();
        assertEquals(7, out.size());
        assertEquals(IDS, out.stream().map(WidgetDescriptor::id).toList());
    }

    @Test
    @DisplayName("each descriptor carries name, category and a positive size")
    void descriptorsAreWellFormed() {
        for (WidgetDescriptor d : new BuiltinWidgetProvider().descriptors()) {
            assertNotNull(d.displayName(), "null name for " + d.id());
            assertFalse(d.displayName().isBlank(), "blank name for " + d.id());
            assertNotNull(d.category(), "null category for " + d.id());
            assertFalse(d.category().isBlank(), "blank category for " + d.id());
            assertTrue(d.defaultWidth() > 0, "non-positive width for " + d.id());
            assertTrue(d.defaultHeight() > 0, "non-positive height for " + d.id());
        }
    }

    @Test
    @DisplayName("the descriptor metadata mirrors the shared card catalogue")
    void metadataMatchesCatalogue() {
        List<WidgetDescriptor> out = new BuiltinWidgetProvider().descriptors();
        for (int i = 0; i < out.size(); i++) {
            WidgetCardSpec spec = BuiltinWidgetCards.all().get(i);
            WidgetDescriptor d = out.get(i);
            assertEquals(spec.id(), d.id());
            assertEquals(spec.displayName(), d.displayName());
            assertEquals(spec.category(), d.category());
            assertEquals(spec.defaultWidth(), d.defaultWidth());
            assertEquals(spec.defaultHeight(), d.defaultHeight());
        }
    }

    @Test
    @DisplayName("descriptors() is repeatable and returns a fresh list each call")
    void isRepeatable() {
        BuiltinWidgetProvider provider = new BuiltinWidgetProvider();
        List<WidgetDescriptor> first = provider.descriptors();
        List<WidgetDescriptor> second = provider.descriptors();
        assertEquals(first.size(), second.size());
        assertEquals(first.stream().map(WidgetDescriptor::id).toList(),
                second.stream().map(WidgetDescriptor::id).toList());
    }
}
