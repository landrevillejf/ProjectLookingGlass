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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the screen-capture save-location helper. The config frame
 * is a {@code JFrame} and cannot be constructed under {@code java.awt.headless},
 * but the pure {@link ScreenCaptureConfigFrame#defaultScreenshotDirectory()}
 * resolver — the piece that decides where snapshots land — is static and touches
 * no window, so it is asserted directly. It must resolve to
 * {@code ~/Documents/Screenshots} (the user's home), never the process working
 * directory the app used before.
 */
class ScreenCaptureConfigFrameTest {

    @Test
    @DisplayName("the default save directory is ~/Documents/Screenshots")
    void defaultDirectoryIsDocumentsScreenshots() {
        File dir = ScreenCaptureConfigFrame.defaultScreenshotDirectory();

        assertNotNull(dir);
        assertEquals("Screenshots", dir.getName());
        assertNotNull(dir.getParentFile());
        assertEquals("Documents", dir.getParentFile().getName());
    }

    @Test
    @DisplayName("the default save directory is rooted at the user's home")
    void defaultDirectoryIsUnderUserHome() {
        File dir = ScreenCaptureConfigFrame.defaultScreenshotDirectory();
        File home = new File(System.getProperty("user.home"));

        assertTrue(dir.getAbsolutePath().startsWith(home.getAbsolutePath()),
            "expected " + dir + " to live under the user home " + home);
        assertEquals(new File(new File(home, "Documents"), "Screenshots"), dir);
    }
}
