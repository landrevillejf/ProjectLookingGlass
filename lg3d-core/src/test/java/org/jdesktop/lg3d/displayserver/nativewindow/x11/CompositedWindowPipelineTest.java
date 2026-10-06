/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link CompositedWindowPipeline} — the presentation-agnostic
 * spine that wires one {@link WindowPixelSource} to one {@link CompositedWindowSink}.
 *
 * <p>The pipeline holds no {@code gnu.x11.Display} and no Java 3D reference, so a
 * {@link FakeSource} and a {@link FakeSink} exercise every dispatch decision
 * (read-then-present, negative-origin clamping, empty-region and null-region
 * short-circuits, resize/dispose forwarding, constructor null checks) with no live
 * X connection and no scene graph.</p>
 */
class CompositedWindowPipelineTest {

    /** A source that records the last read geometry and returns a canned image. */
    private static final class FakeSource implements WindowPixelSource {
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastW = Integer.MIN_VALUE;
        int lastH = Integer.MIN_VALUE;
        int reads;
        BufferedImage result = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

        @Override
        public BufferedImage readRegion(int x, int y, int width, int height) {
            reads++;
            lastX = x;
            lastY = y;
            lastW = width;
            lastH = height;
            return result;
        }
    }

    /** A sink that records the last presented region and lifecycle events. */
    private static final class FakeSink implements CompositedWindowSink {
        BufferedImage region;
        int x;
        int y;
        int w;
        int h;
        int presents;
        int resizeW = Integer.MIN_VALUE;
        int resizeH = Integer.MIN_VALUE;
        int disposes;

        @Override
        public void present(BufferedImage img, int px, int py, int pw, int ph) {
            presents++;
            region = img;
            x = px;
            y = py;
            w = pw;
            h = ph;
        }

        @Override
        public void resized(int width, int height) {
            resizeW = width;
            resizeH = height;
        }

        @Override
        public void dispose() {
            disposes++;
        }
    }

    @Test
    @DisplayName("a damage report reads the region and presents it unchanged")
    void presentsOnDamage() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink);

        pipeline.damageReported(0x1234, 10, 20, 30, 40);

        assertEquals(1, source.reads);
        assertEquals(10, source.lastX);
        assertEquals(20, source.lastY);
        assertEquals(30, source.lastW);
        assertEquals(40, source.lastH);
        assertEquals(1, sink.presents);
        assertSame(source.result, sink.region);
        assertEquals(10, sink.x);
        assertEquals(20, sink.y);
        assertEquals(30, sink.w);
        assertEquals(40, sink.h);
    }

    @Test
    @DisplayName("negative damage origins are clamped to zero for source and sink")
    void clampsNegativeOrigin() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink);

        pipeline.damageReported(0x1, -5, -7, 8, 9);

        assertEquals(0, source.lastX);
        assertEquals(0, source.lastY);
        assertEquals(0, sink.x);
        assertEquals(0, sink.y);
        // Size is preserved even when the origin is clamped.
        assertEquals(8, source.lastW);
        assertEquals(9, source.lastH);
    }

    @Test
    @DisplayName("an empty or negative region is a no-op: no read, no present")
    void emptyRegionIsNoOp() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink);

        pipeline.damageReported(0x1, 0, 0, 0, 10);
        pipeline.damageReported(0x1, 0, 0, 10, 0);
        pipeline.damageReported(0x1, 0, 0, -1, 10);
        pipeline.damageReported(0x1, 0, 0, 10, -1);

        assertEquals(0, source.reads);
        assertEquals(0, sink.presents);
    }

    @Test
    @DisplayName("a null readback is not presented (the next damage retries)")
    void nullRegionNotPresented() {
        FakeSource source = new FakeSource();
        source.result = null;
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink);

        pipeline.damageReported(0x1, 0, 0, 4, 4);

        assertEquals(1, source.reads);
        assertEquals(0, sink.presents);
        assertNull(sink.region);
    }

    @Test
    @DisplayName("resized is forwarded to the sink")
    void resizedForwarded() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink);

        pipeline.resized(800, 600);

        assertEquals(800, sink.resizeW);
        assertEquals(600, sink.resizeH);
    }

    @Test
    @DisplayName("dispose is forwarded to the sink")
    void disposeForwarded() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink);

        pipeline.dispose();

        assertEquals(1, sink.disposes);
    }

    @Test
    @DisplayName("a null source or sink is rejected by the constructor")
    void rejectsNulls() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        assertThrows(IllegalArgumentException.class,
            () -> new CompositedWindowPipeline(null, sink));
        assertThrows(IllegalArgumentException.class,
            () -> new CompositedWindowPipeline(source, null));
        assertThrows(IllegalArgumentException.class,
            () -> new CompositedWindowPipeline(null, null));
    }

    @Test
    @DisplayName("the default sink lifecycle methods are no-ops")
    void defaultSinkMethodsAreNoOps() {
        // A lambda is a valid CompositedWindowSink (one abstract method); its
        // default resized()/dispose() must not throw or require overriding.
        final BufferedImage[] presented = new BufferedImage[1];
        CompositedWindowSink sink = (img, x, y, w, h) -> presented[0] = img;

        BufferedImage region = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        sink.present(region, 0, 0, 1, 1);
        sink.resized(640, 480);
        sink.dispose();

        assertSame(region, presented[0]);
    }

    // ---- Phase E: damage coalescing + frame pacing --------------------

    /** A pacer interval so large only the always-due first present passes. */
    private static FramePacer throttle() {
        return new FramePacer(Long.MAX_VALUE / 2);
    }

    @Test
    @DisplayName("a throttling pacer coalesces damage until flush presents the union")
    void pacerCoalescesUntilFlush() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink, throttle());

        pipeline.damageReported(0x1, 0, 0, 10, 10); // first present is always due
        assertEquals(1, sink.presents);
        assertEquals(10, sink.w);
        assertFalse(pipeline.hasPendingDamage());

        pipeline.damageReported(0x1, 50, 50, 10, 10); // paced out, accumulates
        pipeline.damageReported(0x1, 20, 20, 5, 5);   // paced out, accumulates
        assertEquals(1, sink.presents);               // nothing presented yet
        assertEquals(1, source.reads);
        assertTrue(pipeline.hasPendingDamage());

        pipeline.flush(0x1);                          // presents the union
        assertEquals(2, sink.presents);
        assertEquals(20, sink.x);                     // min(50,20)
        assertEquals(20, sink.y);
        assertEquals(40, sink.w);                     // 20..59
        assertEquals(40, sink.h);
        assertEquals(2, source.reads);                // one coalesced read
        assertFalse(pipeline.hasPendingDamage());
    }

    @Test
    @DisplayName("a null pacer defaults to always-due (synchronous presents)")
    void nullPacerAlwaysDue() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink, null);

        pipeline.damageReported(0x1, 0, 0, 4, 4);
        pipeline.damageReported(0x1, 8, 8, 4, 4);

        assertEquals(2, sink.presents); // each presented synchronously
        assertFalse(pipeline.hasPendingDamage());
    }

    @Test
    @DisplayName("flush is a no-op when no damage is pending")
    void flushNoOpWhenEmpty() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        CompositedWindowPipeline pipeline =
            new CompositedWindowPipeline(source, sink, throttle());

        pipeline.flush(0x1);

        assertEquals(0, source.reads);
        assertEquals(0, sink.presents);
    }

    @Test
    @DisplayName("the 3-arg constructor still rejects a null source or sink")
    void threeArgRejectsNulls() {
        FakeSource source = new FakeSource();
        FakeSink sink = new FakeSink();
        assertThrows(IllegalArgumentException.class,
            () -> new CompositedWindowPipeline(null, sink, throttle()));
        assertThrows(IllegalArgumentException.class,
            () -> new CompositedWindowPipeline(source, null, throttle()));
    }
}
