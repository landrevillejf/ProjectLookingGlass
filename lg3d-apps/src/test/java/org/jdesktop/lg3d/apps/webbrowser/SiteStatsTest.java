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
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link SiteStats} per-host counters. */
class SiteStatsTest {

    @Test
    @DisplayName("an unknown host yields zero counts, never null")
    void unknownHost() {
        SiteStats stats = new SiteStats();
        SiteStats.Counts c = stats.forUrl("https://nothing.example");
        assertEquals(0, c.getNavigationsBlocked());
        assertEquals(0, c.getPopupsBlocked());
        assertEquals(0, c.getTotalBlocked());
    }

    @Test
    @DisplayName("navigation and popup blocks are counted separately per host")
    void countsPerHost() {
        SiteStats stats = new SiteStats();
        stats.recordNavigationBlocked("https://a.example/x");
        stats.recordNavigationBlocked("https://a.example/y");
        stats.recordPopupBlocked("https://a.example/z");
        stats.recordPopupBlocked("https://b.example/");

        SiteStats.Counts a = stats.forUrl("https://a.example/deep/path");
        assertEquals(2, a.getNavigationsBlocked());
        assertEquals(1, a.getPopupsBlocked());
        assertEquals(3, a.getTotalBlocked());

        SiteStats.Counts b = stats.forUrl("https://b.example");
        assertEquals(0, b.getNavigationsBlocked());
        assertEquals(1, b.getPopupsBlocked());
    }

    @Test
    @DisplayName("a bare host is accepted and matched by host key")
    void bareHost() {
        SiteStats stats = new SiteStats();
        stats.recordPopupBlocked("c.example");
        assertEquals(1, stats.forUrl("https://c.example/page").getPopupsBlocked());
        assertEquals(1, stats.forUrl("C.EXAMPLE").getPopupsBlocked(),
                "the host key is case-insensitive");
    }

    @Test
    @DisplayName("null / blank URLs are ignored rather than counted")
    void ignoresBlank() {
        SiteStats stats = new SiteStats();
        stats.recordNavigationBlocked(null);
        stats.recordPopupBlocked("   ");
        assertEquals(0, stats.forUrl(null).getTotalBlocked());
    }

    @Test
    @DisplayName("clear(host) forgets one host; clear() forgets all")
    void clearing() {
        SiteStats stats = new SiteStats();
        stats.recordNavigationBlocked("https://a.example");
        stats.recordNavigationBlocked("https://b.example");
        stats.clear("https://a.example");
        assertEquals(0, stats.forUrl("https://a.example").getTotalBlocked());
        assertEquals(1, stats.forUrl("https://b.example").getTotalBlocked());
        stats.clear();
        assertEquals(0, stats.forUrl("https://b.example").getTotalBlocked());
    }
}
