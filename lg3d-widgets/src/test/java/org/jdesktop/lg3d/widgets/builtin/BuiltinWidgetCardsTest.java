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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.widgets.api.WidgetConfigStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the pure-Swing widget catalogue and the cards it builds: the built-in
 * ids/names/sizes, the {@link WidgetCardSpec} defaults and guards, the shared
 * temperature colour ramp, and the clock card's persisted click interaction.
 *
 * <p>None of this touches Java 3D, so it runs headless - which is exactly the
 * point: the same cards render on the 3D desktop and on a 3D-less 2D one.</p>
 */
class BuiltinWidgetCardsTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // Catalogue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the catalogue lists the five built-ins in gallery order")
    void catalogueListsTheBuiltins() {
        List<WidgetCardSpec> all = BuiltinWidgetCards.all();
        assertEquals(5, all.size());
        assertEquals(List.of("clock", "temperature", "cpu", "memory", "weather"),
                all.stream().map(WidgetCardSpec::id).toList());
    }

    @Test
    @DisplayName("forId/contains resolve built-ins and reject everything else")
    void forIdAndContains() {
        assertNotNull(BuiltinWidgetCards.forId("clock"));
        assertEquals("clock", BuiltinWidgetCards.forId("clock").id());
        assertTrue(BuiltinWidgetCards.contains("cpu"));
        assertTrue(BuiltinWidgetCards.contains("weather"));
        assertFalse(BuiltinWidgetCards.contains("nope"));
        assertFalse(BuiltinWidgetCards.contains(null));
        assertNull(BuiltinWidgetCards.forId("nope"));
        assertNull(BuiltinWidgetCards.forId(null));
    }

    @Test
    @DisplayName("every spec builds a matching card whose size is the default")
    void specsBuildMatchingCards() {
        for (WidgetCardSpec spec : BuiltinWidgetCards.all()) {
            WidgetCard card = spec.create();
            assertNotNull(card, "spec created null: " + spec.id());
            assertEquals(spec.id(), card.id(),
                    "card id must match its spec id");
            assertEquals(spec.defaultWidth(), card.getPreferredSize().width,
                    "catalogue width must match the card: " + spec.id());
            assertEquals(spec.defaultHeight(), card.getPreferredSize().height,
                    "catalogue height must match the card: " + spec.id());
        }
    }

    // ------------------------------------------------------------------
    // WidgetCardSpec guards and defaults
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a spec rejects a blank id or a null factory")
    void specGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new WidgetCardSpec(null, "n", "c", null, 10, 10, ClockCard::new));
        assertThrows(IllegalArgumentException.class,
                () -> new WidgetCardSpec("  ", "n", "c", null, 10, 10, ClockCard::new));
        assertThrows(IllegalArgumentException.class,
                () -> new WidgetCardSpec("x", "n", "c", null, 10, 10, null));
    }

    @Test
    @DisplayName("a spec falls back to sensible defaults for blank/zero inputs")
    void specDefaults() {
        WidgetCardSpec spec = new WidgetCardSpec("x", null, null, null, 0, 0, ClockCard::new);
        assertEquals("x", spec.displayName(), "blank name falls back to the id");
        assertEquals("General", spec.category(), "blank category falls back to General");
        assertEquals(120, spec.defaultWidth());
        assertEquals(80, spec.defaultHeight());
        assertNull(spec.iconResource());
        assertEquals("x [x]", spec.toString());
    }

    // ------------------------------------------------------------------
    // Card behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the clock card toggles analog/digital and persists the choice")
    void clockCardClickPersistsMode() {
        WidgetConfigStore store = new WidgetConfigStore(tmp.resolve("clock.properties"));
        ClockCard card = new ClockCard();
        assertEquals("clock", card.id());
        assertEquals(1000L, card.tickPeriodMillis());

        card.attach(store, "clock-1", Runnable::run);
        card.tick();                       // must not throw headless
        card.onClick();                    // analog -> digital
        assertEquals("digital", store.getOption("clock-1", "mode", null));
        card.onClick();                    // digital -> analog
        assertEquals("analog", store.getOption("clock-1", "mode", null));
    }

    @Test
    @DisplayName("a clock card re-reads its persisted mode on attach")
    void clockCardReadsPersistedMode() {
        WidgetConfigStore store = new WidgetConfigStore(tmp.resolve("clock2.properties"));
        store.setOption("clock-9", "mode", "digital");

        ClockCard first = new ClockCard();
        first.attach(store, "clock-9", Runnable::run);
        first.onClick();                   // digital -> analog
        assertEquals("analog", store.getOption("clock-9", "mode", null));
    }

    @Test
    @DisplayName("a card with no config is inert, not broken")
    void cardWithoutConfigIsSafe() {
        ClockCard bare = new ClockCard();
        bare.attach(null, null, null);
        bare.tick();
        bare.onClick();                    // setOption is a no-op; no NPE
        bare.onWheel(1);
        bare.start();
        bare.stop();
    }

    @Test
    @DisplayName("the shared colour ramp is cool / warm / hot at the thresholds")
    void temperatureColourRamp() {
        assertEquals(new Color(110, 210, 150), TemperatureCard.colorFor(20));
        assertEquals(new Color(110, 210, 150), TemperatureCard.colorFor(59.9));
        assertEquals(new Color(240, 190, 90), TemperatureCard.colorFor(60));  // WARM
        assertEquals(new Color(240, 190, 90), TemperatureCard.colorFor(79.9));
        assertEquals(new Color(240, 110, 90), TemperatureCard.colorFor(80));  // HOT
        assertEquals(new Color(240, 110, 90), TemperatureCard.colorFor(120));
    }

    @Test
    @DisplayName("the temperature card ticks and cycles zones without throwing")
    void temperatureCardIsHeadlessSafe() {
        TemperatureCard card = new TemperatureCard();
        assertEquals("temperature", card.id());
        assertTrue(card.tickPeriodMillis() > 0);
        card.attach(new WidgetConfigStore(tmp.resolve("temp.properties")), "temp-1", Runnable::run);
        card.tick();      // reads sensors; guarded, so safe with none present
        card.onClick();   // cycles only when >1 zone; must not throw either way
    }
}
