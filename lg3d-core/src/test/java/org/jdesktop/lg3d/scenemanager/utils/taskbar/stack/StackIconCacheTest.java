/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link StackIconCache#evictLru(Path, int)}, the least-recently-used
 * bound that keeps the on-disk stack-icon cache from growing forever. The tests
 * run against a {@link TempDir} with explicit last-modified times, so they are
 * fully headless (no display, no real cache directory) and deterministic.
 */
class StackIconCacheTest {

    /** Writes a dummy {@code <name>.png} whose mtime is {@code millis}. */
    private static Path png(Path dir, String name, long millis) throws Exception {
        Path p = dir.resolve(name + ".png");
        Files.write(p, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(p, FileTime.fromMillis(millis));
        return p;
    }

    private static long countPngs(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .count();
        }
    }

    @Test
    @DisplayName("evicts the oldest-used PNGs down to the cap")
    void evictsOldestWhenOverCap(@TempDir Path dir) throws Exception {
        // Five icons, distinct mtimes: a=oldest .. e=newest.
        png(dir, "a", 1_000L);
        png(dir, "b", 2_000L);
        png(dir, "c", 3_000L);
        png(dir, "d", 4_000L);
        png(dir, "e", 5_000L);

        int evicted = StackIconCache.evictLru(dir, 3);

        assertEquals(2, evicted, "two entries are over the cap of three");
        assertEquals(3, countPngs(dir), "exactly the cap remains");
        assertFalse(Files.exists(dir.resolve("a.png")), "oldest evicted first");
        assertFalse(Files.exists(dir.resolve("b.png")), "next-oldest evicted");
        assertTrue(Files.exists(dir.resolve("c.png")));
        assertTrue(Files.exists(dir.resolve("d.png")));
        assertTrue(Files.exists(dir.resolve("e.png")), "newest always kept");
    }

    @Test
    @DisplayName("ranks by last-modified, so a touched old icon survives")
    void touchedFileSurvives(@TempDir Path dir) throws Exception {
        // 'old' was created first but used most recently (touch bumps mtime),
        // so LRU must keep it and evict the genuinely least-recently-used one.
        png(dir, "old", 1_000L);
        Files.setLastModifiedTime(dir.resolve("old.png"),
                FileTime.fromMillis(9_000L));
        png(dir, "stale", 8_000L);
        png(dir, "mid", 5_000L);

        int evicted = StackIconCache.evictLru(dir, 2);

        assertEquals(1, evicted);
        assertTrue(Files.exists(dir.resolve("old.png")), "recently used survives");
        assertFalse(Files.exists(dir.resolve("mid.png")), "least-recent used goes");
        assertTrue(Files.exists(dir.resolve("stale.png")));
    }

    @Test
    @DisplayName("ignores non-PNG files when counting and evicting")
    void leavesNonPngFilesAlone(@TempDir Path dir) throws Exception {
        png(dir, "a", 1_000L);
        png(dir, "b", 2_000L);
        Path txt = dir.resolve("notes.txt");
        Files.write(txt, new byte[] {9});
        Files.setLastModifiedTime(txt, FileTime.fromMillis(0L));

        int evicted = StackIconCache.evictLru(dir, 1);

        assertEquals(1, evicted);
        assertTrue(Files.exists(txt), "a non-PNG is never evicted");
        assertTrue(Files.exists(dir.resolve("b.png")), "newest PNG kept");
        assertFalse(Files.exists(dir.resolve("a.png")));
    }

    @Test
    @DisplayName("does nothing when the cache is at or under the cap")
    void noEvictionWhenUnderCap(@TempDir Path dir) throws Exception {
        png(dir, "a", 1_000L);
        png(dir, "b", 2_000L);

        assertEquals(0, StackIconCache.evictLru(dir, 5), "under the cap");
        assertEquals(0, StackIconCache.evictLru(dir, 2), "exactly at the cap");
        assertEquals(2, countPngs(dir), "nothing removed");
    }

    @Test
    @DisplayName("tolerates empty dirs, null and a zero cap without throwing")
    void edgeCases(@TempDir Path dir) throws Exception {
        assertEquals(0, StackIconCache.evictLru(dir, 10), "empty dir");
        assertEquals(0, StackIconCache.evictLru(null, 10), "null dir");

        png(dir, "a", 1_000L);
        png(dir, "b", 2_000L);
        // A zero cap evicts everything PNG; still returns cleanly.
        assertEquals(2, StackIconCache.evictLru(dir, 0));
        assertEquals(0, countPngs(dir));
    }
}
