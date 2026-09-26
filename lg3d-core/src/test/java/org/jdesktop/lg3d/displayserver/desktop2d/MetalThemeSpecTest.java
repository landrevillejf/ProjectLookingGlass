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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure Metal-theme seam: {@link MetalThemeSpec}'s encode/decode
 * round-trip (including malformed input), the built-in Steel/Ocean palettes,
 * the accent-colour derivation behind "New theme", the list serialisation used
 * to persist custom themes, and the {@link CustomMetalTheme} bridge that maps a
 * spec onto the live {@code Metal} palette. All headless: no look-and-feel is
 * installed and no window is realised.
 */
class MetalThemeSpecTest {

    @Test
    @DisplayName("the built-in Steel and Ocean themes are named and fully coloured")
    void builtInsArePopulated() {
        assertEquals("Steel", MetalThemeSpec.STEEL.name());
        assertEquals("Ocean", MetalThemeSpec.OCEAN.name());
        for (MetalThemeSpec spec : List.of(MetalThemeSpec.STEEL, MetalThemeSpec.OCEAN)) {
            assertNotNull(spec.primary1());
            assertNotNull(spec.primary2());
            assertNotNull(spec.primary3());
            assertNotNull(spec.secondary1());
            assertNotNull(spec.secondary2());
            assertNotNull(spec.secondary3());
        }
    }

    @Test
    @DisplayName("encode -> decode round-trips every field exactly")
    void encodeDecodeRoundTrips() {
        MetalThemeSpec spec = new MetalThemeSpec("My Theme",
                new Color(0x11, 0x22, 0x33), new Color(0x44, 0x55, 0x66),
                new Color(0x77, 0x88, 0x99), new Color(0xAA, 0xBB, 0xCC),
                new Color(0xDD, 0xEE, 0xFF), new Color(0x01, 0x02, 0x03));
        MetalThemeSpec back = MetalThemeSpec.decode(spec.encode());
        assertEquals(spec, back);
        assertEquals(0x112233, back.primary1().getRGB() & 0xFFFFFF);
        assertEquals(0x010203, back.secondary3().getRGB() & 0xFFFFFF);
    }

    @Test
    @DisplayName("a blank name canonicalises to Custom; null colours fall back to Steel")
    void canonicalisesDefaults() {
        MetalThemeSpec spec = new MetalThemeSpec("  ", null, null, null, null, null, null);
        assertEquals("Custom", spec.name());
        assertEquals(MetalThemeSpec.STEEL.primary1().getRGB(), spec.primary1().getRGB());
        assertEquals(MetalThemeSpec.STEEL.secondary3().getRGB(), spec.secondary3().getRGB());
    }

    @Test
    @DisplayName("malformed / null / blank records decode to null")
    void decodeRejectsGarbage() {
        assertNull(MetalThemeSpec.decode(null));
        assertNull(MetalThemeSpec.decode(""));
        assertNull(MetalThemeSpec.decode("   "));
        assertNull(MetalThemeSpec.decode("onlyname"));
        assertNull(MetalThemeSpec.decode("Name;#112233;#445566"));
        assertNull(MetalThemeSpec.decode(";#112233;#445566;#778899;#aabbcc;#ddeeff;#010203"),
                "an empty name is rejected");
        assertNull(MetalThemeSpec.decode("Name;zz2233;#445566;#778899;#aabbcc;#ddeeff;#010203"),
                "a non-hex colour is rejected");
    }

    @Test
    @DisplayName("encodeAll/decodeAll round-trips a list and skips bad records")
    void listSerialisation() {
        List<MetalThemeSpec> specs = List.of(
                MetalThemeSpec.STEEL, MetalThemeSpec.OCEAN,
                MetalThemeSpec.fromAccent("Rose", new Color(0xCC, 0x33, 0x66)));
        String encoded = MetalThemeSpec.encodeAll(specs);
        List<MetalThemeSpec> back = MetalThemeSpec.decodeAll(encoded);
        assertEquals(3, back.size());
        assertEquals("Steel", back.get(0).name());
        assertEquals("Ocean", back.get(1).name());
        assertEquals("Rose", back.get(2).name());

        assertTrue(MetalThemeSpec.decodeAll(null).isEmpty());
        assertTrue(MetalThemeSpec.decodeAll("").isEmpty());
        assertEquals("", MetalThemeSpec.encodeAll(null));
        assertEquals("", MetalThemeSpec.encodeAll(List.of()));
        // A corrupt line among good ones is skipped, not fatal.
        assertEquals(3, MetalThemeSpec.decodeAll(encoded + "\ngarbage-line").size());
    }

    @Test
    @DisplayName("fromAccent keeps the accent as primary2 and derives darker/lighter shades")
    void fromAccentDerives() {
        Color accent = new Color(0x40, 0x80, 0xC0);
        MetalThemeSpec spec = MetalThemeSpec.fromAccent("Blue", accent);
        assertEquals("Blue", spec.name());
        assertEquals(accent.getRGB(), spec.primary2().getRGB());
        assertTrue(spec.primary1().getRed() < accent.getRed(), "primary1 is darker");
        assertTrue(spec.primary3().getRed() > accent.getRed(), "primary3 is lighter");
        // The neutral secondary ramp is borrowed from Steel.
        assertEquals(MetalThemeSpec.STEEL.secondary1().getRGB(), spec.secondary1().getRGB());
        assertEquals(MetalThemeSpec.STEEL.secondary3().getRGB(), spec.secondary3().getRGB());
    }

    @Test
    @DisplayName("CustomMetalTheme maps the spec onto the live Metal palette")
    void customThemeBridge() {
        MetalThemeSpec spec = MetalThemeSpec.fromAccent("Jade", new Color(0x30, 0xA0, 0x60));
        CustomMetalTheme theme = new CustomMetalTheme(spec);
        assertEquals("Jade", theme.getName());
        assertEquals(spec.primary2().getRGB(), theme.getPrimary2().getRGB());
        assertEquals(spec.secondary2().getRGB(), theme.getSecondary2().getRGB());
        assertEquals(spec, theme.spec());
        // A null spec falls back to Steel rather than NPE-ing.
        assertEquals("Steel", new CustomMetalTheme(null).getName());
    }

    @Test
    @DisplayName("the manager exposes the built-ins and resolves them by name")
    void managerResolve() {
        assertEquals(List.of(MetalThemeSpec.STEEL, MetalThemeSpec.OCEAN),
                MetalThemeManager.builtIns());
        assertEquals("Ocean", MetalThemeManager.resolve("Ocean").name());
        assertNull(MetalThemeManager.resolve(null));
        assertNull(MetalThemeManager.resolve("No Such Theme"));
        assertNotNull(MetalThemeManager.toTheme(MetalThemeSpec.STEEL));
    }
}
