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
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdesktop.lg3d.apps.wonderland.WorldCatalog.Cell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the AWT-free {@link WorldCatalog} backend of the Wonderland
 * World app: server-address normalising, the wire document parser, the tree
 * arithmetic the panel draws from a flat list, and the census line. No network
 * and no X display is touched -- the transport is a scripted
 * {@link WorldCatalog.Fetcher}, so this suite runs in CI exactly like
 * {@code OpenMeteoTest}.
 */
class WorldCatalogTest {

    /**
     * A two-level world in the exact shape {@code WorldRestController.describe}
     * writes: id and parent as decimal strings, a null parent on the root, and
     * every cell carrying a class name.
     */
    private static final String SAMPLE = "["
            + "{\"id\":\"1\",\"parent\":null,\"className\":\"org.jdesktop.wonderland.modules.cell.EnvironmentCell\",\"name\":\"Environment\"},"
            + "{\"id\":\"2\",\"parent\":\"1\",\"className\":\"org.jdesktop.wonderland.modules.cell.TestCell\",\"name\":\"Room\"},"
            + "{\"id\":\"3\",\"parent\":\"2\",\"className\":\"org.jdesktop.wonderland.modules.meshmodel.MeshModelCell\",\"name\":null}"
            + "]";

    // ------------------------------------------------------------------
    // Address handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a typed host:port becomes a real http URL")
    void normalizesBareHost() {
        assertEquals("http://localhost:8080", WorldCatalog.normalizeServer("localhost:8080"));
        assertEquals("http://10.0.0.5:9090", WorldCatalog.normalizeServer(" 10.0.0.5:9090 "));
    }

    @Test
    @DisplayName("an explicit scheme is kept and the trailing slash dropped")
    void keepsSchemeAndTrimsSlashes() {
        assertEquals("https://world.example", WorldCatalog.normalizeServer("https://world.example/"));
        assertEquals("http://host:8080", WorldCatalog.normalizeServer("http://host:8080///"));
    }

    @Test
    @DisplayName("an empty server is refused rather than sent as a URL")
    void emptyServerRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldCatalog.normalizeServer(null));
        assertThrows(IllegalArgumentException.class, () -> WorldCatalog.normalizeServer("   "));
    }

    @Test
    @DisplayName("cellsUrl appends the world resource")
    void buildsCellsUrl() {
        assertEquals("http://localhost:8080/api/world/cells",
                WorldCatalog.cellsUrl("localhost:8080"));
        assertEquals(WorldCatalog.DEFAULT_SERVER + WorldCatalog.CELLS_PATH,
                WorldCatalog.cellsUrl(WorldCatalog.DEFAULT_SERVER));
    }

    @Test
    @DisplayName("defaultServer reads lg.wonderland.server.url, blank counts as unset")
    void readsServerProperty() {
        String before = System.getProperty(WorldCatalog.SERVER_PROPERTY);
        try {
            System.clearProperty(WorldCatalog.SERVER_PROPERTY);
            assertEquals(WorldCatalog.DEFAULT_SERVER, WorldCatalog.defaultServer());

            System.setProperty(WorldCatalog.SERVER_PROPERTY, "  ");
            assertEquals(WorldCatalog.DEFAULT_SERVER, WorldCatalog.defaultServer());

            System.setProperty(WorldCatalog.SERVER_PROPERTY, "world:9000/");
            assertEquals("http://world:9000", WorldCatalog.defaultServer());
        } finally {
            if (before == null) {
                System.clearProperty(WorldCatalog.SERVER_PROPERTY);
            } else {
                System.setProperty(WorldCatalog.SERVER_PROPERTY, before);
            }
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the wire document becomes cells in publication order")
    void parsesPublishedWorld() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(SAMPLE);

        assertEquals(3, world.size());
        assertFalse(world.isEmpty());
        Cell room = world.find("2");
        assertNotNull(room);
        assertEquals("1", room.parent);
        assertEquals("Room", room.name);
        assertEquals("TestCell", room.simpleClassName());
    }

    @Test
    @DisplayName("an extra field the reader does not know is ignored")
    void ignoresUnknownFields() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(
                "[{\"id\":\"1\",\"parent\":null,\"className\":\"X\",\"name\":\"Root\","
                + "\"transform\":[1,0,0,0]}]");

        assertEquals(1, world.size());
        assertEquals("Root", world.cells().get(0).label());
    }

    @Test
    @DisplayName("an entry with no id is skipped, not shown as a blank row")
    void skipsIdlessEntry() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(
                "[{\"className\":\"X\"},{\"id\":\"\",\"name\":\"empty\"},"
                + "{\"id\":\"7\",\"className\":\"Y\"}]");

        assertEquals(1, world.size());
        assertEquals("7", world.cells().get(0).id);
        assertNull(world.find("1"));
    }

    @Test
    @DisplayName("an empty array is a world with no cells, not an error")
    void parsesEmptyArray() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse("[]");

        assertTrue(world.isEmpty());
        assertEquals(0, world.size());
        assertTrue(world.roots().isEmpty());
    }

    @Test
    @DisplayName("anything but a JSON array is refused as a world document")
    void refusesNonArray() {
        // A Spring error page answers HTML or an object; reading either as a
        // world would show a tree that does not exist.
        assertThrows(IOException.class, () -> WorldCatalog.parse("{\"cells\":[]}"));
        assertThrows(IOException.class, () -> WorldCatalog.parse("<html>500</html>"));
        assertThrows(IOException.class, () -> WorldCatalog.parse(""));
        assertThrows(IOException.class, () -> WorldCatalog.parse(null));
    }

    @Test
    @DisplayName("the placement fields a server adds become readable cell geometry")
    void parsesCellPlacement() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(
                "[{\"id\":\"1\",\"className\":\"X\",\"name\":\"Placed\","
                + "\"x\":-8.0,\"z\":-5.0,\"width\":8.0,\"depth\":8.0},"
                + "{\"id\":\"2\",\"className\":\"Y\",\"name\":\"NoFootprint\",\"x\":1.0,\"z\":2.0},"
                + "{\"id\":\"3\",\"className\":\"Z\",\"name\":\"HalfPlaced\",\"x\":3.0},"
                + "{\"id\":\"4\",\"className\":\"W\",\"name\":\"Degenerate\","
                + "\"x\":0.0,\"z\":0.0,\"width\":0.0,\"depth\":-2.0}]");

        Cell placed = world.find("1");
        assertTrue(placed.placed());
        assertEquals(-8.0, placed.x);
        assertEquals(-5.0, placed.z);
        assertEquals(8.0, placed.width);
        assertEquals(8.0, placed.depth);

        // a position with no size is still placed: the plan draws it as a unit
        // square rather than dropping the room entirely
        Cell sizeless = world.find("2");
        assertTrue(sizeless.placed());
        assertNull(sizeless.width);
        assertNull(sizeless.depth);

        // a position on one axis only is not placed: inventing the other would
        // be a lie about the world
        assertFalse(world.find("3").placed());

        // a non-positive footprint is no footprint (positiveOrNull): a server
        // that serializes an unbounded box as 0 must not draw a zero-width room
        Cell degenerate = world.find("4");
        assertTrue(degenerate.placed());
        assertNull(degenerate.width);
        assertNull(degenerate.depth);
        // and an absent footprint is the same null, not a surprise 0.0
        assertNull(degenerate.depth);
    }

    @Test
    @DisplayName("a document with no geometry at all parses exactly as before")
    void unplacedCellsKeepTheOldShape() throws IOException {
        Cell root = WorldCatalog.parse(SAMPLE).find("1");
        assertFalse(root.placed());
        assertNull(root.x);
        assertNull(root.width);
    }

    // ------------------------------------------------------------------
    // Label fallback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("label falls back from name to class to id")
    void labelFallback() {
        Cell named = new Cell("1", null, "a.b.SomeCell", "Room");
        Cell classed = new Cell("2", "1", "a.b.MeshModelCell", null);
        Cell bare = new Cell("4", null, null, null);

        assertEquals("Room", named.label());
        assertEquals("MeshModelCell", classed.label());
        assertEquals("4", bare.label());
        // A blank name is no name, and a blank class is no class either: the
        // row still has to read as something, which is the id.
        assertEquals("3", new Cell("3", "1", "", "  ").label());
        // A class with no package is already its own simple name.
        assertEquals("TopLevel", new Cell("5", null, "TopLevel", null).label());
        // With no class at all there is nothing to strip, so the detail row is
        // empty rather than "null".
        assertEquals("", new Cell("6", null, "", null).simpleClassName());
        // The wire's own vocabulary stays reachable for the detail rows.
        assertEquals("a.b.MeshModelCell", classed.className);
    }

    // ------------------------------------------------------------------
    // The tree computed from a flat list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("depth counts parents and roots sit at zero")
    void computesDepth() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(SAMPLE);

        assertEquals(0, world.depthOf("1"));
        assertEquals(1, world.depthOf("2"));
        assertEquals(2, world.depthOf("3"));
        // An id the world does not have is still indentable.
        assertEquals(0, world.depthOf("nope"));
        assertEquals(0, world.depthOf(null));
    }

    @Test
    @DisplayName("roots and children read off the parent links")
    void findsRootsAndChildren() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(SAMPLE);

        assertEquals(List.of("1"), ids(world.roots()));
        assertEquals(List.of("2"), ids(world.childrenOf("1")));
        assertEquals(List.of("3"), ids(world.childrenOf("2")));
        assertTrue(world.childrenOf("3").isEmpty());
        assertTrue(world.childrenOf(null).isEmpty());
    }

    @Test
    @DisplayName("a cell whose parent was never published is a root")
    void orphanIsItsOwnRoot() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(
                "[{\"id\":\"9\",\"parent\":\"404\",\"className\":\"X\",\"name\":\"Orphan\"}]");

        assertEquals(List.of("9"), ids(world.roots()));
        assertEquals(0, world.depthOf("9"));
        assertEquals("Orphan", world.pathOf("9"));
    }

    @Test
    @DisplayName("pathOf spells the chain from the root down")
    void buildsBreadcrumb() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(SAMPLE);

        assertEquals("Environment", world.pathOf("1"));
        assertEquals("Environment / Room", world.pathOf("2"));
        // The unnamed cell labels as its class, so the path stays readable.
        assertEquals("Environment / Room / MeshModelCell", world.pathOf("3"));
        assertEquals("", world.pathOf("nope"));
    }

    @Test
    @DisplayName("a parent cycle terminates instead of hanging")
    void cycleTerminates() throws IOException {
        // 1 -> 2 -> 1: the server's guards should never publish this, but a
        // hand-written document can, and a walk without a seen-set would spin.
        WorldCatalog.WorldView world = WorldCatalog.parse(
                "[{\"id\":\"1\",\"parent\":\"2\",\"className\":\"A\",\"name\":\"One\"},"
                + "{\"id\":\"2\",\"parent\":\"1\",\"className\":\"B\",\"name\":\"Two\"}]");

        // Each cell is walked once, so the climb stops at depth 1 for both.
        assertEquals(1, world.depthOf("1"));
        assertEquals(1, world.depthOf("2"));
        // The breadcrumb is what the walk visited, root-most first; a broken
        // chain has no true root, so it starts where the repeat cut it off.
        assertEquals("Two / One", world.pathOf("1"));
        assertTrue(world.roots().isEmpty());
    }

    // ------------------------------------------------------------------
    // The census line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("summary counts cells, levels and names the root")
    void summarizesWorld() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(SAMPLE);

        assertEquals("3 cells \u00B7 3 levels \u00B7 root: Environment", world.summary());
    }

    @Test
    @DisplayName("one cell is said in the singular")
    void summarizesSingleCell() throws IOException {
        WorldCatalog.WorldView world = WorldCatalog.parse(
                "[{\"id\":\"1\",\"parent\":null,\"className\":\"A\",\"name\":\"Root\"}]");

        assertEquals("1 cell \u00B7 1 level \u00B7 root: Root", world.summary());
    }

    @Test
    @DisplayName("an empty world says so plainly")
    void summarizesEmptyWorld() throws IOException {
        assertEquals("the world has no cells", WorldCatalog.parse("[]").summary());
    }

    // ------------------------------------------------------------------
    // Load through the transport seam
    // ------------------------------------------------------------------

    @Test
    @DisplayName("load fetches the cells URL of the given server")
    void loadUsesNormalizedUrl() throws IOException {
        List<String> requested = new ArrayList<>();
        WorldCatalog.Fetcher recorder = url -> {
            requested.add(url);
            return SAMPLE;
        };

        WorldCatalog.WorldView world = WorldCatalog.load("localhost:8080", recorder);

        assertEquals(List.of("http://localhost:8080/api/world/cells"), requested);
        assertEquals(3, world.size());
    }

    @Test
    @DisplayName("a transport failure surfaces as-is for the status line")
    void loadPropagatesIoFailure() {
        WorldCatalog.Fetcher failing = url -> {
            throw new IOException("HTTP 404");
        };

        IOException ex = assertThrows(IOException.class,
                () -> WorldCatalog.load("localhost:8080", failing));
        assertEquals("HTTP 404", ex.getMessage());
    }

    @Test
    @DisplayName("a missing transport is refused before any request")
    void loadRequiresFetcher() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldCatalog.load("localhost:8080", null));
    }

    // ------------------------------------------------------------------
    // The worlds catalog: list what is available, then open one
    // ------------------------------------------------------------------

    @Test
    @DisplayName("worldsUrl and the per-module cells URL are built from a server")
    void buildsWorldsAndModuleUrls() {
        assertEquals("http://localhost:8080/api/world/worlds",
                WorldCatalog.worldsUrl("localhost:8080"));
        // a plain module name needs no escaping
        assertEquals("http://localhost:8080/api/world/cells?module=wonderland-town",
                WorldCatalog.cellsUrl("localhost:8080", "wonderland-town"));
        // a name with a query-unsafe character is percent-encoded, not sent raw
        assertEquals("http://localhost:8080/api/world/cells?module=a%23b",
                WorldCatalog.cellsUrl("localhost:8080", "a#b"));
        // the preview resource follows the same rule: a server, a module, encoded
        assertEquals("http://localhost:8080/api/world/preview?module=a%23b",
                WorldCatalog.previewUrl("localhost:8080", "a#b"));
    }

    @Test
    @DisplayName("the worlds document becomes entries in server order")
    void parsesWorldsDocument() throws IOException {
        List<WorldCatalog.WorldEntry> worlds = WorldCatalog.parseWorlds(
                "[{\"module\":\"clinic\",\"title\":\"Outpatient Clinic\","
                + "\"description\":\"a clinic\",\"source\":\"classpath\",\"cellCount\":9},"
                + "{\"module\":\"town\",\"title\":\"Town\",\"description\":\"\","
                + "\"source\":\"jar\",\"cellCount\":2}]");

        assertEquals(2, worlds.size());
        assertEquals("clinic", worlds.get(0).module());
        assertEquals("Outpatient Clinic", worlds.get(0).title());
        assertEquals(9, worlds.get(0).cellCount());
        // the picker label always names the module too, so two same-titled worlds
        // are still distinguishable
        assertEquals("Outpatient Clinic (clinic)", worlds.get(0).displayLabel());
        // a title-less entry falls back to the module for its label
        assertEquals("Town (town)", worlds.get(1).displayLabel());
    }

    @Test
    @DisplayName("the worlds document marks which module the server hosts live")
    void parsesWorldsHostedFlag() throws IOException {
        List<WorldCatalog.WorldEntry> worlds = WorldCatalog.parseWorlds(
                "[{\"module\":\"clinic\",\"title\":\"Clinic\",\"source\":\"classpath\","
                + "\"cellCount\":9,\"hosted\":true},"
                + "{\"module\":\"town\",\"title\":\"Town\",\"source\":\"jar\",\"cellCount\":2}]");

        assertTrue(worlds.get(0).hosted());
        // a server that does not send the field says nothing about hosting,
        // which reads as "not hosted" rather than failing the whole catalog
        assertFalse(worlds.get(1).hosted());
    }

    @Test
    @DisplayName("an entry with no module is skipped, and a non-array is refused")
    void skipsModulelessEntryAndRefusesNonArray() throws IOException {
        List<WorldCatalog.WorldEntry> worlds = WorldCatalog.parseWorlds(
                "[{\"title\":\"no module\"},{\"module\":\"x\",\"title\":\"X\","
                + "\"source\":\"jar\",\"cellCount\":1}]");
        assertEquals(1, worlds.size());
        assertEquals("x", worlds.get(0).module());
        assertTrue(WorldCatalog.parseWorlds("[]").isEmpty());
        assertThrows(IOException.class, () -> WorldCatalog.parseWorlds("<html>500</html>"));
        assertThrows(IOException.class, () -> WorldCatalog.parseWorlds("{\"worlds\":[]}"));
        assertThrows(IOException.class, () -> WorldCatalog.parseWorlds(""));
    }

    @Test
    @DisplayName("loadWorlds fetches the worlds URL of the given server")
    void loadWorldsUsesNormalizedUrl() throws IOException {
        List<String> requested = new ArrayList<>();
        WorldCatalog.Fetcher recorder = url -> {
            requested.add(url);
            return "[{\"module\":\"clinic\",\"title\":\"Clinic\","
                    + "\"source\":\"classpath\",\"cellCount\":9}]";
        };

        List<WorldCatalog.WorldEntry> worlds = WorldCatalog.loadWorlds("localhost:8080", recorder);

        assertEquals(List.of("http://localhost:8080/api/world/worlds"), requested);
        assertEquals(1, worlds.size());
    }

    @Test
    @DisplayName("load(server, module) fetches that world's cells URL")
    void loadModuleUsesModuleUrl() throws IOException {
        List<String> requested = new ArrayList<>();
        WorldCatalog.Fetcher recorder = url -> {
            requested.add(url);
            return SAMPLE;
        };

        WorldCatalog.WorldView world = WorldCatalog.loadWorld("localhost:8080", "clinic", recorder);

        assertEquals(List.of("http://localhost:8080/api/world/cells?module=clinic"), requested);
        assertEquals(3, world.size());
    }

    @Test
    @DisplayName("a missing transport is refused before a module or worlds read")
    void moduleAndWorldsLoadsRequireFetcher() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldCatalog.loadWorld("localhost:8080", "clinic", null));
        assertThrows(IllegalArgumentException.class,
                () -> WorldCatalog.loadWorlds("localhost:8080", null));
    }

    // ------------------------------------------------------------------
    // The preview image: bytes, and a 404 that is an answer rather than a fault
    // ------------------------------------------------------------------

    @Test
    @DisplayName("loadPreview asks the preview URL of the module it names")
    void loadPreviewUsesPreviewUrl() throws IOException {
        List<String> requested = new ArrayList<>();
        WorldCatalog.ByteFetcher recorder = url -> {
            requested.add(url);
            return Optional.of(new byte[] {1, 2, 3});
        };

        Optional<byte[]> bytes =
                WorldCatalog.loadPreview("localhost:8080", "clinic", recorder);

        assertEquals(List.of("http://localhost:8080/api/world/preview?module=clinic"),
                requested);
        assertEquals(3, bytes.orElseThrow().length);
    }

    @Test
    @DisplayName("a world with no picture reads as empty, not as a failure")
    void loadPreviewTreatsAbsentAsAnAnswer() throws IOException {
        WorldCatalog.ByteFetcher none = url -> Optional.empty();

        assertTrue(WorldCatalog.loadPreview("localhost:8080", "clinic", none).isEmpty());
    }

    @Test
    @DisplayName("a missing binary transport is refused before the read")
    void loadPreviewRequiresFetcher() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldCatalog.loadPreview("localhost:8080", "clinic",
                        (WorldCatalog.ByteFetcher) null));
    }

    private static List<String> ids(List<Cell> cells) {
        List<String> out = new ArrayList<>();
        for (Cell cell : cells) {
            out.add(cell.id);
        }
        return out;
    }
}
