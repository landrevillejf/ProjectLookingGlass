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
package org.jdesktop.lg3d.apps.screencapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless coverage of the snapshot file plumbing: the default directory, the
 * 3D-compatible file naming, the on-demand {@code mkdirs}, the shared image
 * counter and the PNG writer (exercised against a temporary folder).
 */
class ScreenshotFilesTest {

    @Test
    @DisplayName("the default directory is ~/Documents/Screenshots")
    void defaultDirectory() {
        File dir = ScreenshotFiles.defaultDirectory();
        assertNotNull(dir);
        assertEquals("Screenshots", dir.getName());
        assertNotNull(dir.getParentFile());
        assertEquals("Documents", dir.getParentFile().getName());
        File home = new File(System.getProperty("user.home"));
        assertEquals(new File(new File(home, "Documents"), "Screenshots"), dir);
    }

    @Test
    @DisplayName("names match the 3D lgscreen-<i>-<n>.<fmt> convention")
    void naming() {
        assertEquals("lgscreen-0-5.png", ScreenshotFiles.nameFor(0, 5, "png"));
        assertEquals("lgscreen-2-17.png",
                ScreenshotFiles.nameFor(2, 17, "png"));
    }

    @Test
    @DisplayName("ensureDirectory creates missing parents and passes nulls through")
    void ensureDirectory(@TempDir Path tmp) {
        File nested = new File(tmp.toFile(), "a/b/c");
        assertFalse(nested.exists());
        assertSame(nested, ScreenshotFiles.ensureDirectory(nested));
        assertTrue(nested.isDirectory());
        // Idempotent on an existing dir, and null-safe.
        assertSame(nested, ScreenshotFiles.ensureDirectory(nested));
        assertNull(ScreenshotFiles.ensureDirectory(null));
    }

    @Test
    @DisplayName("the image counter hands out increasing numbers")
    void counterIncrements() {
        int a = ScreenshotFiles.nextNumber();
        int b = ScreenshotFiles.nextNumber();
        assertEquals(a + 1, b);
    }

    @Test
    @DisplayName("write encodes a PNG and rejects null arguments")
    void write(@TempDir Path tmp) {
        BufferedImage image = new BufferedImage(3, 2,
                BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x00FF00);
        File out = new File(tmp.toFile(), "shot.png");
        assertTrue(ScreenshotFiles.write(image, out, "png"));
        assertTrue(out.isFile());
        assertTrue(out.length() > 0);
        // A null format falls back to the default (png) rather than failing.
        File out2 = new File(tmp.toFile(), "shot2.png");
        assertTrue(ScreenshotFiles.write(image, out2, null));
        assertTrue(out2.isFile());
        // Null guards degrade to "not written".
        assertFalse(ScreenshotFiles.write(null, out, "png"));
        assertFalse(ScreenshotFiles.write(image, null, "png"));
    }
}
