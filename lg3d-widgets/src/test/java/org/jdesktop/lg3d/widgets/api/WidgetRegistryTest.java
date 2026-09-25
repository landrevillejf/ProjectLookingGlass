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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WidgetRegistry}, the {@link java.util.ServiceLoader}-backed
 * singleton that aggregates every {@link WidgetProvider} on the classpath.
 *
 * <p>On the test classpath the registry discovers two providers: the module's
 * own {@code BuiltinWidgetProvider} (the seven built-in ids) and this module's
 * test-only {@link StubWidgetProvider}, registered through
 * {@code src/test/resources/META-INF/services}. The stub contributes a
 * Java 3D-free widget so {@link WidgetRegistry#create(String)} can be driven
 * down its <em>success</em> path headlessly; creating a real built-in widget
 * would instantiate a Java 3D {@code Component3D}, which is probe-verified
 * rather than unit-tested.</p>
 */
class WidgetRegistryTest {

    private static final List<String> BUILTINS =
            List.of("clock", "calendar", "temperature", "cpu", "memory", "indicators", "weather");

    /** Every id the registry is expected to discover on the test classpath. */
    private static final List<String> DISCOVERED =
            java.util.stream.Stream.concat(BUILTINS.stream(),
                    java.util.stream.Stream.of(StubWidgetProvider.STUB_ID))
                    .sorted().toList();

    @Test
    @DisplayName("getInstance() returns the same shared singleton")
    void singletonIsShared() {
        WidgetRegistry a = WidgetRegistry.getInstance();
        WidgetRegistry b = WidgetRegistry.getInstance();
        assertNotNull(a);
        assertSame(a, b);
    }

    @Test
    @DisplayName("the SPI-discovered registry lists the built-ins plus the test stub")
    void discoversBuiltins() {
        WidgetRegistry reg = WidgetRegistry.getInstance();
        List<String> discovered = reg.descriptors().stream()
                .map(WidgetDescriptor::id).sorted().toList();
        assertEquals(DISCOVERED, discovered,
                "registry should expose the built-in ids plus the test stub");
    }

    @Test
    @DisplayName("descriptors() is unmodifiable")
    void descriptorsAreUnmodifiable() {
        List<WidgetDescriptor> all = WidgetRegistry.getInstance().descriptors();
        assertThrows(UnsupportedOperationException.class,
                () -> all.add(new WidgetDescriptor("x", "n", "c", null, 1, 1,
                        () -> null)));
    }

    @Test
    @DisplayName("contains()/descriptor() resolve known ids and reject unknown")
    void lookupById() {
        WidgetRegistry reg = WidgetRegistry.getInstance();
        for (String id : BUILTINS) {
            assertTrue(reg.contains(id), "expected built-in id: " + id);
            WidgetDescriptor d = reg.descriptor(id);
            assertNotNull(d);
            assertEquals(id, d.id());
        }
        assertFalse(reg.contains("does-not-exist"));
        assertNull(reg.descriptor("does-not-exist"));
    }

    @Test
    @DisplayName("create() returns null for an unknown id rather than throwing")
    void createUnknownIsNull() {
        assertNull(WidgetRegistry.getInstance().create("does-not-exist"));
    }

    @Test
    @DisplayName("create() builds a widget for a known id via its descriptor factory")
    void createKnownIdSucceeds() {
        Widget w = WidgetRegistry.getInstance().create(StubWidgetProvider.STUB_ID);
        assertNotNull(w, "the stub descriptor's factory should produce a widget");
        assertEquals(StubWidgetProvider.STUB_ID, w.id());
    }
}
