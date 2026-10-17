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
package org.jdesktop.lg3d.displayserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;

import org.jdesktop.lg3d.displayserver.ScreenCaptureEvent.CaptureMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link ScreenCaptureEvent}, in particular the optional
 * crop region the Screen Snapshot app's "selection" area relies on. The event
 * is a plain {@code LgEvent} value object, so it constructs and asserts freely
 * without a display.
 */
class ScreenCaptureEventTest {

    @Test
    @DisplayName("the single-arg constructor defaults to a whole-canvas png")
    void defaults() {
        ScreenCaptureEvent evt = new ScreenCaptureEvent("/tmp/shots");
        assertEquals("/tmp/shots", evt.getDirectory());
        assertEquals("png", evt.getFormat());
        assertEquals(CaptureMode.SINGLE, evt.getCaptureMode());
        assertEquals(0, evt.getFrameCount());
        assertFalse(evt.hasRegion());
        assertNull(evt.getRegion());
    }

    @Test
    @DisplayName("the mode constructor and setters round-trip")
    void modeAndCount() {
        ScreenCaptureEvent evt =
                new ScreenCaptureEvent(CaptureMode.START_COUNT, "/d");
        assertEquals(CaptureMode.START_COUNT, evt.getCaptureMode());
        evt.setCaptureMode(CaptureMode.STOP);
        assertEquals(CaptureMode.STOP, evt.getCaptureMode());
        evt.setFrameCount(7);
        assertEquals(7, evt.getFrameCount());
    }

    @Test
    @DisplayName("a region round-trips and hasRegion reports it")
    void regionRoundTrip() {
        ScreenCaptureEvent evt = new ScreenCaptureEvent("/d");
        evt.setRegion(new Rectangle(10, 20, 30, 40));
        assertTrue(evt.hasRegion());
        assertEquals(new Rectangle(10, 20, 30, 40), evt.getRegion());
    }

    @Test
    @DisplayName("the region is copied in and out, so callers cannot mutate it")
    void regionIsDefensivelyCopied() {
        ScreenCaptureEvent evt = new ScreenCaptureEvent("/d");
        Rectangle in = new Rectangle(1, 2, 3, 4);
        evt.setRegion(in);
        in.x = 999; // mutating the caller's rect must not leak in
        assertEquals(1, evt.getRegion().x);

        Rectangle out = evt.getRegion();
        assertNotSame(out, evt.getRegion());
        out.width = 999; // mutating the returned copy must not leak in
        assertEquals(3, evt.getRegion().width);
    }

    @Test
    @DisplayName("a null region clears it back to a whole-canvas capture")
    void nullRegionClears() {
        ScreenCaptureEvent evt = new ScreenCaptureEvent("/d");
        evt.setRegion(new Rectangle(0, 0, 5, 5));
        assertTrue(evt.hasRegion());
        evt.setRegion(null);
        assertFalse(evt.hasRegion());
        assertNull(evt.getRegion());
    }
}
