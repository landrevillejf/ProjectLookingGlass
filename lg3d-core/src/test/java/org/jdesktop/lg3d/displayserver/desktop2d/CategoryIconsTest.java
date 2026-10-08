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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import javax.swing.Icon;
import com.protonmail.landrevillejf.MissingIcon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link CategoryIcons}: every shipped start-menu category resolves to a
 * real IconManager glyph (never the red-X {@link MissingIcon}, never null), an
 * uncurated name falls back through {@link AppIcons}, and a non-bundled size is
 * resized rather than degraded. The bundled IconManager library is on the test
 * classpath, so real icons are built headless (no window is ever shown).
 */
class CategoryIconsTest {

    /** The category groups the shipped {@code startmenu.lgcfg} declares. */
    private static final String[] SHIPPED_CATEGORIES = {
        "Internet", "Utilities", "Games", "Media", "Office", "Education",
        "Developers", "System", "Tests", "Demos", "Early Prototypes",
    };

    @AfterEach
    void restore() {
        // The cache is a process-wide static shared across the whole suite.
        CategoryIcons.clearCache();
    }

    @Test
    @DisplayName("every shipped category resolves to a real 16px glyph")
    void shippedCategoriesResolve() {
        for (String category : SHIPPED_CATEGORIES) {
            Icon icon = CategoryIcons.iconFor(category, 16);
            assertNotNull(icon, category + " gets a category icon");
            assertEquals(16, icon.getIconWidth(), category + " icon width");
            assertEquals(16, icon.getIconHeight(), category + " icon height");
            assertFalse(icon instanceof MissingIcon,
                    category + " must never degrade to the red-X MissingIcon");
        }
    }

    @Test
    @DisplayName("resolution is case-insensitive on the category name")
    void caseInsensitive() {
        assertNotNull(CategoryIcons.iconFor("internet", 16));
        assertFalse(CategoryIcons.iconFor("INTERNET", 16) instanceof MissingIcon);
        assertFalse(CategoryIcons.iconFor("Media", 16) instanceof MissingIcon);
    }

    @Test
    @DisplayName("a non-bundled size resizes the glyph, never the MissingIcon")
    void nonBundledSizeResizesGlyph() {
        // The bundled toolbar glyphs only ship at 16/24; a naive
        // loadIcon(...,22,22) returns the red-X MissingIcon. iconFor must load a
        // bundled edge and resize instead.
        Icon icon = CategoryIcons.iconFor("Internet", 22);
        assertNotNull(icon, "a curated category resolves at a non-bundled size");
        assertEquals(22, icon.getIconWidth());
        assertEquals(22, icon.getIconHeight());
        assertFalse(icon instanceof MissingIcon,
                "a curated glyph must never degrade to the red-X MissingIcon");
    }

    @Test
    @DisplayName("an uncurated category falls back through AppIcons, never null")
    void uncuratedFallsBackToAppIcons() {
        Icon icon = CategoryIcons.iconFor("Zzzzqqq", 16);
        assertNotNull(icon, "an unmatched group still gets an (initials-tile) icon");
        assertEquals(16, icon.getIconWidth());
        assertFalse(icon instanceof MissingIcon);
    }

    @Test
    @DisplayName("a null or blank category degrades to a placeholder, never null")
    void blankStillResolves() {
        assertNotNull(CategoryIcons.iconFor(null, 16));
        assertNotNull(CategoryIcons.iconFor("   ", 16));
    }

    @Test
    @DisplayName("the same category and size caches one icon")
    void cacheSharesOneInstance() {
        Icon first = CategoryIcons.iconFor("System", 16);
        Icon again = CategoryIcons.iconFor("System", 16);
        assertSame(first, again, "a repeat lookup is served from the cache");
    }

    @Test
    @DisplayName("clearCache leaves resolution working")
    void clearCacheKeepsResolving() {
        assertNotNull(CategoryIcons.iconFor("Games", 16));
        CategoryIcons.clearCache();
        assertNotNull(CategoryIcons.iconFor("Games", 16),
                "the cache rebuilds after being cleared");
    }
}
