/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M8: the render path's runtime evidence, in its own JVM.
 *
 * The project rule is that a visual change is proven with pixels, not with a
 * compile ("report that evidence in the PR instead of claiming untested
 * success" -- AGENTS.md). For a world renderer the honest pixel is a frame taken
 * out of the live Java 3D canvas, which needs an X display, a GL context and a
 * wonderland-server answering on the wire. CI has none of those, so this suite
 * is skipped unless asked for:
 *
 *   DISPLAY=:0 ./gradlew :lg3d-apps:test --tests '*WorldRenderProbeTest' \
 *       -Dlg.wonderland.probe=true
 *
 * What it writes is the artifact: one PNG per world under
 * {@code build-gradle/scratch/}, the same place the desktop's own screencaptures
 * live and, like them, never committed. The assertion beside each picture is
 * deliberately weak-on-purpose and strong where it matters: a preview is not
 * "some image", it is an image with more than a sky's worth of distinct colours
 * in it, because a render that came back as one flat rectangle is exactly the
 * silent-empty failure this phase exists to end.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Renders a catalog world for real and keeps the frame as evidence. Not a CI
 * test: see the header for how to run it and what it needs.
 */
@EnabledIfSystemProperty(named = "lg.wonderland.probe", matches = "true")
class WorldRenderProbeTest {

    /**
     * Where the frames land. Relative to the test task's working directory, which
     * Gradle sets to the module, so the directory is made rather than assumed --
     * and the absolute path is printed, because an artifact nobody can find is an
     * artifact nobody will look at.
     */
    private static final File OUT_DIR = new File("build-gradle/scratch");

    /**
     * The worlds to prove up: every stock world the installer ships -- so the
     * claim "the port reads all seven real worlds" is checked with a frame for
     * each, not for one -- plus the port's own bundled clinic.
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

    @DisplayName("each listed world renders a frame with real colour in it")
    @Test
    void rendersEveryListedWorld() throws IOException {
        assertTrue(OUT_DIR.mkdirs() || OUT_DIR.isDirectory(),
                "nowhere to put the evidence: " + OUT_DIR.getAbsolutePath());
        String server = WorldCatalog.defaultServer();
        Set<String> rendered = new java.util.HashSet<>();
        for (String module : MODULES) {
            WorldCatalog.WorldView world = WorldCatalog.loadWorld(server, module);
            WorldRender.Attempt attempt = WorldRender.tryPreview(server, module,
                    world, WorldCatalog.HTTP_BYTE_FETCHER);
            File out = new File(OUT_DIR, "render-" + module + ".png");
            BufferedImage frame = attempt.frame();
            assertTrue(frame != null, module + ": Java 3D answered no frame -- "
                    + attempt.failure() + " (server " + server + ", "
                    + world.size() + " cells, headless="
                    + java.awt.GraphicsEnvironment.isHeadless() + ", display="
                    + System.getenv("DISPLAY") + ")");
            ImageIO.write(frame, "png", out);
            int colours = WorldRender.distinctColors(frame);
            assertTrue(colours > 8, module + ": the frame is " + frame.getWidth()
                    + "x" + frame.getHeight() + " with only " + colours
                    + " distinct colours -- that is a sky, not a world (" + out
                    + ")");
            rendered.add(module + "=" + colours);
            System.out.println("[probe] " + out.getAbsolutePath());
        }
        System.out.println("[probe] rendered frames written to "
                + OUT_DIR.getAbsolutePath() + ": " + rendered);
    }
}
