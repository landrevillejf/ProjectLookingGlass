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
package org.jdesktop.lg3d.apps.controlcenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jdesktop.lg3d.apps.controlcenter.ControlPanelRegistry.PanelDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the control center's lazy category registration.
 *
 * <p>{@link ControlPanelRegistry#descriptors()} must list every category by
 * name <em>without</em> constructing its panel - that laziness is what stops the
 * control center from freezing on the many panels that block on platform I/O in
 * their constructors. A {@link PanelDescriptor} therefore builds its panel only
 * on the first {@link PanelDescriptor#get()}, memoizes it, and swallows a build
 * failure (returning {@code null}) rather than taking the whole window down.</p>
 */
class ControlPanelRegistryTest {

    /** A trivial panel used to observe lazy construction and memoization. */
    private static final class FakePanel implements ControlPanel {
        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public Icon icon() {
            return null;
        }

        @Override
        public JComponent component() {
            return new JPanel();
        }
    }

    @Test
    @DisplayName("descriptors list every category by name without building it")
    void descriptorsListCategoriesLazily() {
        List<PanelDescriptor> ds = ControlPanelRegistry.descriptors();
        assertFalse(ds.isEmpty(), "the built-in categories are registered");
        assertTrue(ds.stream().anyMatch(d -> d.displayName().equals("Desktop")),
                "the Desktop category (which holds the taskbar config) is present");
        assertTrue(ds.stream().anyMatch(d -> d.displayName().equals("Appearance")));
        long distinct = ds.stream().map(PanelDescriptor::displayName).distinct().count();
        assertEquals(ds.size(), distinct, "category names are unique");
    }

    @Test
    @DisplayName("a descriptor defers construction until get() and memoizes it")
    void descriptorIsLazyAndMemoized() {
        AtomicInteger builds = new AtomicInteger();
        FakePanel fake = new FakePanel();
        PanelDescriptor d = new PanelDescriptor("Lazy", () -> {
            builds.incrementAndGet();
            return fake;
        });

        assertEquals("Lazy", d.displayName());
        assertEquals(0, builds.get(), "naming the category builds nothing");

        assertSame(fake, d.get());
        assertSame(fake, d.get());
        assertEquals(1, builds.get(), "the factory runs once and is memoized");
    }

    @Test
    @DisplayName("a descriptor whose panel throws yields null instead of propagating")
    void descriptorSwallowsBuildFailure() {
        PanelDescriptor d = new PanelDescriptor("Bad", () -> {
            throw new IllegalStateException("no Java 3D runtime here");
        });
        assertNull(d.get(), "a build failure degrades to null");
        assertNull(d.get(), "a failed build is not retried");
    }
}
