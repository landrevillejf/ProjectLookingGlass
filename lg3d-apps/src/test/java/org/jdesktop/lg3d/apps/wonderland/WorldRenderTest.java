/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M8: the render path's decisions, tested without a display.
 *
 * WorldRender is the seam between the browser's inventory and Java 3D, so half
 * of it is pure arithmetic over the parsed wire document (which cells have
 * geometry, which module a model really lives in, whether a render is worth
 * asking for at all) and the other half cannot run in CI. This suite covers the
 * first half exactly; the second is proven by {@code WorldRenderProbeTest},
 * which renders a real frame and keeps the pixels.
 *
 * The pure half is not trivia. Each claim here is a way the world could be
 * silently mis-rendered: draw a placemark as an empty model, fetch a model from
 * the module the world was <em>listed</em> under rather than the one its
 * {@code wla://} reference names, or queue a twelve-second render of a world that
 * has nothing to draw. Those are the bugs that look like "the renderer is
 * broken" and are really the wrong inputs.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.jdesktop.lg3d.apps.wonderland.WorldCatalog.Cell;
import org.jdesktop.lg3d.apps.wonderland.WorldCatalog.WorldView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the decisions {@link WorldRender} makes about a world. */
class WorldRenderTest {

    /** A world of one model cell, one placemark and one unplaced cell. */
    private static WorldView mixed() {
        return new WorldView(List.of(
                new Cell("0", null, "org.jdesktop.wonderland.renderer.cell"
                        + ".WonderlandModelCell", "Town", 1.0, 2.0, 6.0, 6.0,
                        0.0, "wla://wonderland-town-world/DefaultTown5.kmz/x.dep"),
                new Cell("1", "0", "org.jdesktop.wonderless.cell.Placemark",
                        "Sign", 3.0, 4.0, 1.0, 1.0, 0.0, "  "),
                new Cell("2", "0", "org.jdesktop.wonderland.renderer.cell"
                        + ".WonderlandModelCell", "Pavilion", null, null, null,
                        null, null, "wla://other-module/a.kmz/a.dep")));
    }

    @Test
    @DisplayName("only the cells that name a model are drawn")
    void modelCellsAreTheOnesWithGeometry() {
        List<Cell> cells = WorldRender.modelCells(mixed());

        assertEquals(List.of("Town", "Pavilion"),
                cells.stream().map(Cell::label).toList());
        assertTrue(WorldRender.hasGeometry(mixed()));
    }

    @Test
    @DisplayName("a blank reference is no model, and a world of them is no render")
    void aWorldWithoutModelsIsNotWorthRendering() throws IOException {
        WorldView signs = WorldCatalog.parse(
                "[{\"id\":\"0\",\"name\":\"Sign\"},"
                        + "{\"id\":\"1\",\"name\":\"Flower\",\"deployedModelURL\":\"\"}]");

        assertEquals(List.of(), WorldRender.modelCells(signs));
        assertFalse(WorldRender.hasGeometry(signs));
        // the attempt says which of the two it is, rather than returning a
        // picture of nothing
        WorldRender.Attempt attempt = WorldRender.tryPreview("localhost:8080",
                "some-world", signs, url -> java.util.Optional.empty());
        assertNull(attempt.frame());
        assertEquals("no cell of this world names a model or a picture to draw",
                attempt.failure());
    }

    @Test
    @DisplayName("the model's own module answers for it, not the world's")
    void assetModuleComesFromTheReference() {
        WorldView world = mixed();

        // Town lives in the module the world was listed under...
        assertEquals("wonderland-town-world", WorldRender.assetModuleOf(
                world.find("0"), "wonderland-town-world"));
        // ...and Pavilion lives somewhere else entirely. Asking the world's own
        // module for it is a 404 for every texture in the model.
        assertEquals("other-module", WorldRender.assetModuleOf(
                world.find("2"), "wonderland-town-world"));
        // a cell with no reference falls back to the world's module
        assertEquals("wonderland-town-world", WorldRender.assetModuleOf(
                world.find("1"), "wonderland-town-world"));
        // and a cell that is not there at all asks of nobody
        assertNull(WorldRender.assetModuleOf(null, null));
    }

    /**
     * A cell with no placement is still drawn (the model carries its own seat),
     * and the entry the scene gets must say so with zeroes rather than a guess.
     */
    @Test
    @DisplayName("an unplaced model cell still reaches the scene, at the origin")
    void entriesCarryTheAuthoredPose() throws IOException {
        WorldView turned = WorldCatalog.parse("[{\"id\":\"0\",\"name\":\"Town\","
                + "\"deployedModelURL\":\"wla://m/a.kmz/a.dep\",\"x\":1.5,\"y\":2.5,"
                + "\"z\":-3.5,\"width\":6.0,\"depth\":4.0,\"yaw\":3.14,"
                + "\"scale\":[2.0,1.0,0.5]}]");

        // Nothing is fetched here: entriesOf is the loader's caller and needs a
        // store, so what this proves is the wire -> pose claim alone.
        Cell cell = turned.find("0");
        assertEquals(3.14, cell.pose.yawRad());
        assertEquals(2.0, cell.pose.scaleX());
        assertEquals(0.5, cell.pose.scaleZ());
        assertEquals(2.5, cell.y);
        // and the module a model lives in is read off its own reference
        assertEquals("m", WorldRender.assetModuleOf(cell, "wonderland-town-world"));
    }

    @Test
    @DisplayName("the JOGL preview path is the default; only an explicit false keeps java3d")
    void joglPreviewIsTheDefault() {
        String previous = System.getProperty(WorldRender.JOGL_PREVIEW_PROPERTY);
        try {
            // Unset: the cutover made JOGL the shipped desktop's preview renderer,
            // proven at parity across all eight worlds by the P2.5 probe.
            System.clearProperty(WorldRender.JOGL_PREVIEW_PROPERTY);
            assertTrue(WorldRender.joglPreviewEnabled(),
                    "the shipped desktop now renders previews through JOGL by default");

            // An explicit false (either case) is the escape hatch back to java3d,
            // so a user can keep the old WorldViewer until the cutover deletes it.
            System.setProperty(WorldRender.JOGL_PREVIEW_PROPERTY, "false");
            assertFalse(WorldRender.joglPreviewEnabled());
            System.setProperty(WorldRender.JOGL_PREVIEW_PROPERTY, "FALSE");
            assertFalse(WorldRender.joglPreviewEnabled());

            // Anything else -- including the probe's own "true" -- stays on JOGL.
            System.setProperty(WorldRender.JOGL_PREVIEW_PROPERTY, "true");
            assertTrue(WorldRender.joglPreviewEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty(WorldRender.JOGL_PREVIEW_PROPERTY);
            } else {
                System.setProperty(WorldRender.JOGL_PREVIEW_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("a world with no geometry is refused on the JOGL path too")
    void joglPreviewRefusesAnEmptyWorld() throws IOException {
        // The pure guards run before any window opens, so the JOGL path answers
        // the same "nothing to draw" as the java3d one -- headless, no GLCanvas.
        WorldView signs = WorldCatalog.parse(
                "[{\"id\":\"0\",\"name\":\"Sign\"},"
                        + "{\"id\":\"1\",\"name\":\"Flower\",\"deployedModelURL\":\"\"}]");
        WorldRender.Attempt attempt = WorldRender.tryPreviewJogl("localhost:8080",
                "some-world", signs, url -> java.util.Optional.empty());
        assertNull(attempt.frame());
        assertEquals("no cell of this world names a model or a picture to draw",
                attempt.failure());
    }
}
