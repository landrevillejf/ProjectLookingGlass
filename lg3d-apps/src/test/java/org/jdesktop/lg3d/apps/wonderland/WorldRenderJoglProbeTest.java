/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland P2.5: the JOGL render path's runtime evidence, in its own JVM.
 *
 * The opt-in JOGL preview (WorldRender.tryPreviewJogl over GlWorldBuilder and
 * GlWorldViewer) is the path the cutover will make the only one, so before a
 * single java3d file is deleted it has to be proven -- with pixels and a frame
 * rate, not with a compile. This probe is that proof: every stock world plus the
 * port's own clinic is built into a GlScene, shown in a real GLCanvas window with
 * the full post-process chain ON, read back, and timed.
 *
 * It is skipped unless asked for, exactly like WorldRenderProbeTest, because it
 * needs an X display, a JOGL GL context and a wonderland-server answering on the
 * wire -- none of which CI has:
 *
 *   DISPLAY=:0 ./gradlew :lg3d-apps:test --tests '*WorldRenderJoglProbeTest' \
 *       -Dlg.wonderland.jogl.probe=true
 *
 * What it writes is the artifact: one PNG per world under
 * {@code build-gradle/scratch/} (never committed) and a per-world line of
 * colours + presented-frame rate on stdout. The gates are the parity claim: a
 * world the java3d probe renders must render here too, with the same
 * more-than-a-sky's-worth of distinct colours, and the whole five-effect chain
 * must be live (not silently degraded) while it does.
 *
 * The frame rate is the achieved presented-frame rate under GlWorldViewer's
 * 60 Hz animator: a world that keeps up reads near 60, and one whose effects cost
 * more than a vsync reads below it -- which is the honest number, since nothing
 * here renders faster than the swap the window is capped to.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

import org.jdesktop.wonderland.renderer.view.GlWorldBuilder;
import org.jdesktop.wonderland.renderer.view.GlWorldViewer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Renders every listed world through the JOGL path for real, keeps the frame and
 * measures the frame rate. Not a CI test: see the header for how to run it.
 */
@EnabledIfSystemProperty(named = "lg.wonderland.jogl.probe", matches = "true")
class WorldRenderJoglProbeTest {

    /** Where the frames land; made rather than assumed, path printed. */
    private static final File OUT_DIR = new File("build-gradle/scratch");

    /**
     * The worlds to prove up: every stock world the installer ships plus the
     * port's own bundled clinic -- the same eight the java3d probe renders, so a
     * green run here is a like-for-like parity claim, not a narrower one.
     */
    private static final List<String> MODULES = List.of(
            "wonderland-almostempty-world",
            "wonderland-auditorium-world",
            "wonderland-combined-world",
            "wonderland-gallery-world",
            "wonderland-japanesepavilion-world",
            "wonderland-meetingcomplex-world",
            "wonderland-town-world",
            "wonderland-outpatient-clinic");

    /** The vignette size the shipped preview uses; the probe renders the same. */
    private static final int WIDTH = WorldRender.PREVIEW_WIDTH_PX;
    private static final int HEIGHT = WorldRender.PREVIEW_HEIGHT_PX;

    /** How long to wait for the first coloured frame, per world. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 12_000;

    /** How many presented frames to time for the frame-rate number. */
    private static final int FPS_FRAMES = 90;

    /** The full chain: SSAO, bloom, HDR, DOF, FXAA -- all on by default. */
    private static final int FULL_CHAIN = 5;

    @DisplayName("every listed world renders through JOGL with effects on, and keeps up")
    @Test
    void rendersEveryListedWorldThroughJogl() throws IOException {
        assertTrue(OUT_DIR.mkdirs() || OUT_DIR.isDirectory(),
                "nowhere to put the evidence: " + OUT_DIR.getAbsolutePath());
        String server = WorldCatalog.defaultServer();
        StringBuilder report = new StringBuilder();
        report.append("[jogl-probe] server=").append(server)
                .append(" display=").append(System.getenv("DISPLAY"))
                .append(" size=").append(WIDTH).append('x').append(HEIGHT)
                .append(" chain=").append(FULL_CHAIN).append(" effects\n");
        for (String module : MODULES) {
            WorldCatalog.WorldView world = WorldCatalog.loadWorld(server, module);
            GlWorldBuilder.Result built = GlWorldBuilder.build(
                    WorldRender.entriesOf(server, module, world,
                            WorldCatalog.HTTP_BYTE_FETCHER),
                    WorldRender.picturesOf(server, module, world,
                            WorldCatalog.HTTP_BYTE_FETCHER),
                    WIDTH, HEIGHT);
            assertTrue(built.placed() + built.pictures() > 0, module
                    + ": nothing of the world's " + world.size() + " cells loaded ("
                    + built.skipped() + " skipped), so there is nothing to prove");

            GlWorldViewer viewer = new GlWorldViewer(module + " \u2014 JOGL probe",
                    built.scene(), built.camera(), WIDTH, HEIGHT);
            try {
                viewer.show();
                // Effects must be live, not silently degraded: the whole point of
                // the JOGL path is that the chain runs without a preSwap() patch.
                assertEquals(FULL_CHAIN, viewer.getView().passes().size(), module
                        + ": the post-process chain is not fully on");

                BufferedImage first = firstColouredFrame(viewer);
                assertNotNull(first, module
                        + ": JOGL answered no coloured frame in "
                        + FIRST_FRAME_TIMEOUT_MS + " ms");
                File out = new File(OUT_DIR, "jogl-" + module + ".png");
                ImageIO.write(first, "png", out);
                int colours = WorldRender.distinctColors(first);
                assertTrue(colours > 8, module + ": the frame is " + first.getWidth()
                        + "x" + first.getHeight() + " with only " + colours
                        + " distinct colours -- that is a sky, not a world (" + out
                        + ")");

                double fps = measureFps(viewer);
                report.append("[jogl-probe] ").append(module)
                        .append(": placed=").append(built.placed())
                        .append(" pictures=").append(built.pictures())
                        .append(" colours=").append(colours)
                        .append(String.format(" fps=%.1f", fps))
                        .append(" -> ").append(out.getName()).append('\n');
                System.out.println("[jogl-probe] " + out.getAbsolutePath()
                        + " colours=" + colours + String.format(" fps=%.1f", fps));
            } finally {
                viewer.close();
            }
        }
        System.out.println(report);
    }

    /** The first presented frame that holds a picture rather than a cleared buffer. */
    private static BufferedImage firstColouredFrame(GlWorldViewer viewer) {
        long deadline = System.currentTimeMillis() + FIRST_FRAME_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            BufferedImage frame = viewer.capture(1000);
            if (frame != null && WorldRender.hasColour(frame)) {
                return frame;
            }
        }
        return null;
    }

    /**
     * The achieved presented-frame rate: time {@link #FPS_FRAMES} on-demand
     * captures against the animator's 60 Hz clock. A frame the capture could not
     * read still counts against the elapsed time, so a stall lowers the number
     * rather than being skipped over.
     */
    private static double measureFps(GlWorldViewer viewer) {
        int presented = 0;
        long start = System.nanoTime();
        for (int i = 0; i < FPS_FRAMES; i++) {
            if (viewer.capture(1000) != null) {
                presented++;
            }
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        return (seconds <= 0.0) ? 0.0 : presented / seconds;
    }
}
