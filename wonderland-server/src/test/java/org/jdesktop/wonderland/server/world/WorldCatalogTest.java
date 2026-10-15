/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server (world library): WorldCatalog discovery.
 *
 * The catalog is the "which worlds can I get" answer. These prove the two origins
 * merge into one view and that discovery never lets one bad jar hide the rest:
 *   - the bundled classpath clinic is always listed, titled from its module.xml,
 *     with its real cell count (nine), even with no worlds directory configured;
 *   - a world jar dropped into the configured directory appears alongside it,
 *     sourced as a jar, titled from its own module.xml;
 *   - a corrupt jar (not a real zip) degrades to a name-only row rather than
 *     throwing or vanishing, so the library listing is resilient;
 *   - worldJar(module) resolves the jar the seeder will open, and empty when the
 *     module is not installed.
 * Headless: builds small synthetic jars in a temp directory; no Spring context.
 */
package org.jdesktop.wonderland.server.world;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorldCatalogTest {

    private static final String CLINIC = "wonderland-outpatient-clinic";

    @TempDir
    Path dir;

    private static void writeWorldJar(Path jar, String module, String title,
            String... cellNames) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "module.xml", "<wonderland-module><name>" + title
                    + "</name><description>desc of " + module + "</description>"
                    + "</wonderland-module>");
            for (String cell : cellNames) {
                put(out, "wfs/" + module + "-wfs/" + cell + "-wlc.xml",
                        "<model-cell><name>" + cell + "</name></model-cell>");
            }
        }
    }

    private static void put(JarOutputStream jar, String name, String body) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(body.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    @Test
    void theBundledClinicIsListedWithItsRealCellCountEvenWithoutADirectory() {
        WorldCatalog catalog = new WorldCatalog("");

        List<WorldSummary> worlds = catalog.available();
        WorldSummary clinic = worlds.stream()
                .filter(w -> CLINIC.equals(w.module())).findFirst().orElseThrow();

        assertThat(clinic.source()).isEqualTo(WorldSummary.SOURCE_CLASSPATH);
        assertThat(clinic.title()).isEqualTo(CLINIC);
        assertThat(clinic.cellCount()).isEqualTo(24);
    }

    @Test
    void aWorldJarInTheDirectoryAppearsSourcedAsAJarTitledFromItsModuleXml()
            throws IOException {
        writeWorldJar(dir.resolve("wonderland-town-world.jar"),
                "wonderland-town-world", "A Town", "room", "plaza");
        WorldCatalog catalog = new WorldCatalog(dir.toString());

        WorldSummary town = catalog.available().stream()
                .filter(w -> "wonderland-town-world".equals(w.module()))
                .findFirst().orElseThrow();

        assertThat(town.source()).isEqualTo(WorldSummary.SOURCE_JAR);
        assertThat(town.title()).isEqualTo("A Town");
        assertThat(town.description()).isEqualTo("desc of wonderland-town-world");
        assertThat(town.cellCount()).isEqualTo(2);
        assertThat(catalog.worldJar("wonderland-town-world"))
                .contains(dir.resolve("wonderland-town-world.jar"));
    }

    @Test
    void aCorruptJarDegradesToANameOnlyRowRatherThanHidingTheLibrary()
            throws IOException {
        Path bad = dir.resolve("wonderland-broken-world.jar");
        try (OutputStream out = Files.newOutputStream(bad)) {
            out.write("this is not a zip file".getBytes(StandardCharsets.UTF_8));
        }
        writeWorldJar(dir.resolve("wonderland-town-world.jar"),
                "wonderland-town-world", "A Town", "room");

        WorldCatalog catalog = new WorldCatalog(dir.toString());
        List<WorldSummary> worlds = catalog.available();

        // both rows survive; the broken one falls back to its file-stem title
        assertThat(worlds).extracting(WorldSummary::module)
                .contains("wonderland-town-world", "wonderland-broken-world", CLINIC);
        assertThat(worlds).filteredOn(w -> "wonderland-broken-world".equals(w.module()))
                .allSatisfy(w -> assertThat(w.cellCount()).isZero());
    }

    @Test
    void worldJarIsEmptyForAModuleThatIsNotInstalled() {
        WorldCatalog catalog = new WorldCatalog(dir.toString());
        assertThat(catalog.worldJar("no-such-world")).isEmpty();
        assertThat(catalog.worldJar(null)).isEmpty();
    }

    @Test
    void anUnconfiguredDirectoryListsOnlyTheBundledWorlds() {
        // a null worlds directory and a path that is not a directory are both
        // "no installed worlds": the catalog still offers the bundled clinic
        assertThat(new WorldCatalog(null).available())
                .extracting(WorldSummary::module)
                .containsExactly(WorldSeeder.CLINIC_MODULE);
        assertThat(new WorldCatalog(dir.resolve("absent").toString()).available())
                .extracting(WorldSummary::module)
                .containsExactly(WorldSeeder.CLINIC_MODULE);
    }

    @Test
    void aWorldJarWithoutAModuleXmlIsListedByItsFileName() throws IOException {
        Path jar = dir.resolve("wonderland-naked-world.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "wfs/naked-wfs/room-wlc.xml",
                    "<model-cell><name>Room</name></model-cell>");
        }

        WorldSummary naked = new WorldCatalog(dir.toString()).available().stream()
                .filter(w -> "wonderland-naked-world".equals(w.module()))
                .findFirst().orElseThrow();

        assertThat(naked.title()).isEqualTo("wonderland-naked-world");
        assertThat(naked.description()).isEmpty();
        assertThat(naked.cellCount()).isEqualTo(1);
    }

    @Test
    void aModuleXmlMissingNameAndDescriptionDegradesToDefaults() throws IOException {
        Path jar = dir.resolve("wonderland-bare-xml-world.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "module.xml", "<wonderland-module></wonderland-module>");
        }

        WorldSummary bare = new WorldCatalog(dir.toString()).available().stream()
                .filter(w -> "wonderland-bare-xml-world".equals(w.module()))
                .findFirst().orElseThrow();

        assertThat(bare.title()).isEqualTo("wonderland-bare-xml-world");
        assertThat(bare.description()).isEmpty();
    }

    // ---- Stage 2: openAssetBytes, the seam the HTTP asset endpoint reads through.

    @Test
    void openAssetResolvesBytesFromAnInstalledWorldJar() throws IOException {
        Path jar = dir.resolve("wonderland-town-world.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            put(out, "town.kmz/room.ldr", "LDR-DATA");
        }
        assertThat(new WorldCatalog(dir.toString())
                .openAssetBytes("wonderland-town-world", "town.kmz/room.ldr"))
                .hasValue("LDR-DATA".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void openAssetResolvesBytesFromABundledClasspathWorld() {
        // the clinic ships on the classpath; the bundled branch reads it directly
        assertThat(new WorldCatalog("").openAssetBytes(CLINIC, "module.xml"))
                .isPresent();
    }

    @Test
    void openAssetIsDefensiveForBlankInputAnAbsentModuleAndACorruptJar()
            throws IOException {
        WorldCatalog empty = new WorldCatalog("");
        // the null/blank guard short-circuits before any store is touched
        assertThat(empty.openAssetBytes(null, "a")).isEmpty();
        assertThat(empty.openAssetBytes("", "a")).isEmpty();
        assertThat(empty.openAssetBytes(CLINIC, null)).isEmpty();
        assertThat(empty.openAssetBytes(CLINIC, "")).isEmpty();
        // a module that is neither bundled nor installed resolves to nothing
        assertThat(empty.openAssetBytes("no-such-world", "a.ldr")).isEmpty();

        // a file that claims to be a world jar but is not a zip degrades to empty,
        // never a throw -- the same never-500 contract the listing already keeps
        Path bad = dir.resolve("wonderland-broken.jar");
        Files.write(bad, "not a jar".getBytes(StandardCharsets.UTF_8));
        assertThat(new WorldCatalog(dir.toString())
                .openAssetBytes("wonderland-broken", "any.ldr")).isEmpty();
    }

    // ---- describeCells / loadContents: reading any catalog world's cells on
    // ---- demand, independent of the single one the server seeds.

    @Test
    void describeCellsReadsEveryNamedCellOfTheBundledClinic() {
        List<Map<String, Object>> cells = new WorldCatalog("").describeCells(CLINIC);

        // the twenty-four authored cells (rooms, furniture, residents and the
        // wall art hung in them), without the environment root the seeder adds
        assertThat(cells).hasSize(24);
        assertThat(cells).extracting(c -> c.get("name")).contains(
                "Floor", "Exam Room 1", "Reception", "Clinic Directory",
                "Receptionist", "Waiting Patient",
                "Health Notices", "Clinic Window", "Framed Picture");
        // a module's cells are siblings: every parent is null
        assertThat(cells).allSatisfy(c -> assertThat(c.get("parent")).isNull());
        // a room/furniture cell carries a model and reads as the renderer's model
        // cell; a resident carries no model and reads as the avatar cell
        assertThat(cells)
                .filteredOn(c -> WorldSeeder.MODEL_CELL_CLASS.equals(c.get("className")))
                .allSatisfy(c -> assertThat(c).containsKey("deployedModelURL"));
        assertThat(cells).filteredOn(
                c -> WorldSeeder.AVATAR_CELL_CLASS.equals(c.get("className")))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c).doesNotContainKey("deployedModelURL"));
        // ids are unique and namespaced to the module, so a browser can select one
        assertThat(cells).extracting(c -> c.get("id").toString())
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).startsWith(CLINIC + ":"));
        // every authored room carries its real placement, so a browser can
        // preview the world from above without seeding anything: the clinic's
        // exam rooms sit at their authored spots with their authored footprints
        assertThat(cells).allSatisfy(c -> assertThat(c).containsKeys("x", "z"));
        Map<String, Object> exam1 = cells.stream()
                .filter(c -> "Exam Room 1".equals(c.get("name")))
                .findFirst().orElseThrow();
        assertThat(exam1.get("x")).isEqualTo(-8.25f);
        assertThat(exam1.get("z")).isEqualTo(-11.0f);
        // bounds are the MODEL's half-extents and the descriptor reports the
        // PLACED footprint, so width is 2 * half-extent * stretch. The clinic's
        // room is a 1.012 m unit cube (0.506 half-extent, from its own .dep)
        // stretched across a 5.4 m bay and 5.9 m deep.
        //
        // This is the assertion that keeps the two views of a world agreeing. A
        // cell that authors bounds as though they were the placed size makes the
        // product come out squared, and the 2D plan draws a 5.4 m exam room as a
        // 58 m one -- a floor plan of overlapping blobs, while the 3D view, which
        // reads the .dep and never looks at bounds, is correct. The two readers
        // of one world disagreeing is the defect; the number is only how it shows.
        assertThat((Double) exam1.get("width")).isEqualTo(2 * 0.506 * 5.4, within(1e-3));
        assertThat((Double) exam1.get("depth")).isEqualTo(2 * 0.506 * 5.9, within(1e-3));
    }

    @Test
    void theClinicsWallArtNamesAPictureThatTheAssetStoreCanActuallyServe() {
        // The hall's posters, panes and prints are GUI cells: they carry no model,
        // so everything a viewer has to draw them is in the descriptor -- the type
        // it was written as, the file it shows, and the measure of one of its
        // pixels. A mistyped art/ path is the failure this guards: the cell still
        // loads, still places, and silently arrives as a blank card, which is the
        // same nothing a missing model would have made of it.
        WorldCatalog catalog = new WorldCatalog("");
        List<Map<String, Object>> art = catalog.describeCells(CLINIC).stream()
                .filter(c -> c.containsKey("cellType"))
                .toList();

        assertThat(art).as("the hall's wall art").hasSize(6);
        assertThat(art).allSatisfy(cell -> {
            assertThat(cell.get("cellType")).isEqualTo("poster-cell");
            assertThat(cell.get("assetURL").toString())
                    .startsWith("wla://" + CLINIC + "/art/");
            assertThat(cell).containsKey("planeScale");
            // hung on a wall, so it is placed and turned but not stretched: the
            // pixel scale is the whole of its measure
            assertThat(cell).containsKeys("x", "y", "z", "yaw");
            assertThat((List<?>) cell.get("planeScale")).allSatisfy(
                    scale -> assertThat((Double) scale).isGreaterThan(0.0));
            // the file it names must exist in the module it names
            String path = cell.get("assetURL").toString()
                    .substring(("wla://" + CLINIC + "/").length());
            assertThat(catalog.openAssetBytes(CLINIC, path))
                    .as("%s serves %s", CLINIC, path).isPresent();
        });
        // two windows on the far wall, and they are the wide ones
        assertThat(art).filteredOn(c -> "Clinic Window".equals(c.get("name")))
                .hasSize(2)
                .allSatisfy(c -> {
                    List<?> scale = (List<?>) c.get("planeScale");
                    assertThat((Double) scale.get(0)).isGreaterThan(
                            (Double) scale.get(1));
                });
    }

    /**
     * The hall's inner wall faces, in metres from its centre. Measured off
     * {@code clinic-room.dae}'s own vertices -- 22 of them sit at exactly
     * |x| = 0.5 and |z| = 0.5, which the cell's 22 x 16 m scale turns into 11.0
     * and 8.0 -- and not off the {@code .dep} bounding box, whose 0.506 is the
     * outer edge of the wall's thickness. Both numbers were tried, and the one
     * taken from the box hung two framed prints exactly coplanar with the
     * plaster, where the wall wins the depth test and the print is simply not
     * drawn: no error, no blank card, nothing in the frame.
     */
    private static final double HALL_HALF_X = 11.0;
    private static final double HALL_HALF_Z = 8.0;

    @Test
    void everyWallArtCardClearsThePlasterItHangsOn() {
        // A card is one flat face, so hanging it means standing it off a wall by
        // more than a depth buffer and less than a hand's width. This is the
        // geometric half of the guard above: a file that exists and a plane that
        // loads are both still invisible if the plane is inside its own wall.
        WorldCatalog catalog = new WorldCatalog("");
        List<Map<String, Object>> art = catalog.describeCells(CLINIC).stream()
                .filter(c -> c.containsKey("cellType"))
                .toList();
        assertThat(art).hasSize(6);
        for (Map<String, Object> cell : art) {
            // The placement travels as the float the resolver handed back, so it
            // is read as a Number rather than assumed to be a Double.
            double x = ((Number) cell.get("x")).doubleValue();
            double z = ((Number) cell.get("z")).doubleValue();
            double yaw = (Double) cell.get("yaw");
            // The card's front normal is its local +Z turned by the yaw; the wall
            // it hangs on is the one that normal points away from.
            double normalX = Math.sin(yaw);
            double normalZ = Math.cos(yaw);
            double gap;
            if (Math.abs(normalX) > Math.abs(normalZ)) {
                gap = (normalX > 0) ? x + HALL_HALF_X : HALL_HALF_X - x;
            } else {
                gap = (normalZ > 0) ? z + HALL_HALF_Z : HALL_HALF_Z - z;
            }
            assertThat(gap)
                    .as("%s stood off its wall by more than a depth buffer",
                            cell.get("name"))
                    .isBetween(0.10, 0.30);
        }
    }

    @Test
    void describeCellsClassifiesAJarCellWithoutAModelAsTheEnvironmentClass()
            throws IOException {
        writeWorldJar(dir.resolve("wonderland-town-world.jar"),
                "wonderland-town-world", "A Town", "room");

        List<Map<String, Object>> cells = new WorldCatalog(dir.toString())
                .describeCells("wonderland-town-world");

        assertThat(cells).hasSize(1);
        assertThat(cells.get(0).get("name")).isEqualTo("room");
        // a cell authored without a position component carries no placement:
        // the keys are absent, not zeroed -- "nowhere" is not "at the origin"
        assertThat(cells.get(0)).doesNotContainKeys("x", "z", "width", "depth");
        assertThat(cells.get(0).get("className")).isEqualTo(WorldModel.ENVIRONMENT_CLASS);
        // a non-model cell carries no deployed model URL in its descriptor
        assertThat(cells.get(0)).doesNotContainKey("deployedModelURL");
    }

    @Test
    void describeCellsAndLoadContentsAreDefensiveForUnknownAndBrokenWorlds()
            throws IOException {
        WorldCatalog catalog = new WorldCatalog("");
        // the null/blank guard short-circuits before any store is consulted
        assertThat(catalog.describeCells(null)).isEmpty();
        assertThat(catalog.describeCells("  ")).isEmpty();
        assertThat(catalog.loadContents(null)).isEmpty();
        // a module that is neither bundled nor installed resolves to nothing
        assertThat(catalog.describeCells("no-such-world")).isEmpty();
        assertThat(catalog.loadContents("no-such-world")).isEmpty();

        // a file that claims to be a world jar but is not a zip degrades to empty,
        // never a throw -- the same never-500 contract the asset read already keeps
        Path bad = dir.resolve("wonderland-broken-world.jar");
        Files.write(bad, "not a jar".getBytes(StandardCharsets.UTF_8));
        WorldCatalog jarCatalog = new WorldCatalog(dir.toString());
        assertThat(jarCatalog.loadContents("wonderland-broken-world")).isEmpty();
        assertThat(jarCatalog.describeCells("wonderland-broken-world")).isEmpty();
    }

    // ---- preview: the picture a world ships, so the browser can show what a
    // ---- world looks like instead of only where its rooms are.

    @Test
    void aWorldsOwnPreviewPngIsItsPictureWithTheMediaTypeItsNameDeclares()
            throws IOException {
        String module = "wonderland-town-world";
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(dir.resolve(module + ".jar")))) {
            put(out, "module.xml", "<wonderland-module><name>A Town</name></wonderland-module>");
            put(out, "preview.png", "PNG-BYTES");
        }

        WorldCatalog.Preview preview = new WorldCatalog(dir.toString())
                .preview(module).orElseThrow();

        assertThat(new String(preview.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("PNG-BYTES");
        assertThat(preview.contentType()).isEqualTo("image/png");
    }

    @Test
    void aPreviewUnderTheJarsArtRootResolvesTooAndCarriesItsOwnType() throws IOException {
        String module = "wonderland-town-world";
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(dir.resolve(module + ".jar")))) {
            put(out, "module.xml", "<wonderland-module><name>A Town</name></wonderland-module>");
            // the stock world jars store their art under art/; the jar store's
            // own fallback makes the module-root spelling find it there
            put(out, "art/preview.jpg", "JPG-BYTES");
        }

        assertThat(new WorldCatalog(dir.toString()).preview(module))
                .get()
                .extracting(WorldCatalog.Preview::contentType)
                .isEqualTo("image/jpeg");
    }

    @Test
    void aVerifiedAliasResolvesThePictureFromAnotherModulesJar() throws IOException {
        // the pavilion borrows the one real world view in the shipped library,
        // which lives in the combined world's jar -- the alias table exists for
        // exactly that, and this proves the read crosses the store boundary
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(dir.resolve("wonderland-combined-world.jar")))) {
            put(out, "module.xml", "<wonderland-module><name>Combined</name></wonderland-module>");
            put(out, "art/Japanese-Pavilion-portal3.png", "PORTAL-BYTES");
        }

        WorldCatalog.Preview preview = new WorldCatalog(dir.toString())
                .preview("wonderland-japanesepavilion-world").orElseThrow();

        assertThat(new String(preview.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("PORTAL-BYTES");
        assertThat(preview.contentType()).isEqualTo("image/png");
    }

    @Test
    void theWorldsOwnPreviewWinsOverTheAliasTable() throws IOException {
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(dir.resolve("wonderland-japanesepavilion-world.jar")))) {
            put(out, "module.xml", "<wonderland-module><name>Pavilion</name></wonderland-module>");
            put(out, "preview.png", "MY-OWN");
        }
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(dir.resolve("wonderland-combined-world.jar")))) {
            put(out, "module.xml", "<wonderland-module><name>Combined</name></wonderland-module>");
            put(out, "art/Japanese-Pavilion-portal3.png", "BORROWED");
        }

        WorldCatalog.Preview preview = new WorldCatalog(dir.toString())
                .preview("wonderland-japanesepavilion-world").orElseThrow();

        assertThat(new String(preview.bytes(), StandardCharsets.UTF_8)).isEqualTo("MY-OWN");
    }

    @Test
    void aWorldWithNoPictureHasNoPreviewAndNothingAboutThatThrows() throws IOException {
        writeWorldJar(dir.resolve("wonderland-town-world.jar"),
                "wonderland-town-world", "A Town", "room");
        WorldCatalog catalog = new WorldCatalog(dir.toString());

        // a listed world with no art is the common case, not an error
        assertThat(catalog.preview("wonderland-town-world")).isEmpty();
        assertThat(catalog.preview(null)).isEmpty();
        assertThat(catalog.preview("  ")).isEmpty();
        assertThat(catalog.preview("no-such-world")).isEmpty();
        // an alias whose source jar is simply absent degrades the same way
        assertThat(catalog.preview("wonderland-japanesepavilion-world")).isEmpty();
    }

    @Test
    void describeCellsCarriesAForeignCellsTypeAssetPlaneAndPlacement() throws IOException {
        // A cell JAXB will not open (an unported poster type) is recovered by the
        // foreign reader, and its descriptor must carry the two facts a client needs
        // to draw it -- what it was written as and what it shows -- plus the plane
        // measure and the absolute placement folded from its own position component.
        String module = "wonderland-posters-world";
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(dir.resolve(module + ".jar")))) {
            put(out, "module.xml", "<wonderland-module><name>Posters</name></wonderland-module>");
            put(out, "wfs/" + module + "-wfs/lobby-wlc.xml",
                    "<poster-cell><name>Lobby Poster</name>"
                    + "<image-uri>wla://" + module + "/art/lobby.png</image-uri>"
                    + "<preferredWidth>100</preferredWidth><preferredHeight>80</preferredHeight>"
                    + "<position-component>"
                    + "<translation><x>1</x><y>2</y><z>3</z></translation>"
                    + "<scale><x>2</x><y>2</y><z>2</z></scale>"
                    + "<bounds><type>BOX</type><x>4</x><y>1</y><z>5</z></bounds>"
                    + "</position-component></poster-cell>");
            // a second poster with no asset and no position: the absent branches
            put(out, "wfs/" + module + "-wfs/bare-wlc.xml",
                    "<poster-cell><name>Bare</name></poster-cell>");
        }

        List<Map<String, Object>> cells = new WorldCatalog(dir.toString())
                .describeCells(module);
        assertThat(cells).hasSize(2);

        Map<String, Object> lobby = cells.stream()
                .filter(c -> "Lobby Poster".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(lobby.get("className")).isEqualTo(WorldModel.ENVIRONMENT_CLASS);
        assertThat(lobby.get("cellType")).isEqualTo("poster-cell");
        assertThat(lobby.get("assetURL")).isEqualTo("wla://" + module + "/art/lobby.png");
        assertThat(lobby.get("planePixels")).isEqualTo(List.of(100.0, 80.0));
        assertThat(lobby).containsEntry("x", 1.0f).containsEntry("z", 3.0f);
        // the authored stretch is carried, and the footprint is 2 * half-extent * scale
        assertThat(lobby.get("scale")).isEqualTo(List.of(2.0, 2.0, 2.0));
        assertThat(lobby.get("width")).isEqualTo(16.0);
        assertThat(lobby.get("depth")).isEqualTo(20.0);

        // a poster with neither an asset nor a position: no assetURL, no placement
        Map<String, Object> bare = cells.stream()
                .filter(c -> "Bare".equals(c.get("name"))).findFirst().orElseThrow();
        assertThat(bare).doesNotContainKey("assetURL");
        assertThat(bare).doesNotContainKeys("x", "z", "width", "depth");
    }

    @Test
    void yawOfReadsTheHeadingOffAQuaternionWithoutAWorld() {
        // The standalone extraction the descriptor's yaw is taken from: null and a
        // degenerate zero quaternion are no rotation (0.0), and a turn about the
        // vertical axis reads back as its angle even when the quaternion is unnormalised.
        assertThat(WorldCatalog.yawOf(null)).isEqualTo(0.0);
        assertThat(WorldCatalog.yawOf(new com.jme.math.Quaternion(0f, 0f, 0f, 0f)))
                .isEqualTo(0.0);
        assertThat(WorldCatalog.yawOf(new com.jme.math.Quaternion())).isEqualTo(0.0);
        com.jme.math.Quaternion turnY = new com.jme.math.Quaternion()
                .fromAngleAxis((float) Math.toRadians(45), new com.jme.math.Vector3f(0, 1, 0));
        assertThat(WorldCatalog.yawOf(turnY)).isCloseTo(Math.toRadians(45), within(1e-3));
    }

    @Test
    void onlyTheTwoPreviewFormatsHaveAMediaType() {
        assertThat(WorldCatalog.contentTypeOf("preview.png")).isEqualTo("image/png");
        assertThat(WorldCatalog.contentTypeOf("art/VIEW.JPG")).isEqualTo("image/jpeg");
        assertThat(WorldCatalog.contentTypeOf("preview.jpeg")).isEqualTo("image/jpeg");
        // a stray format is not silently served as a PNG; the name says octet-stream
        assertThat(WorldCatalog.contentTypeOf("preview.gif"))
                .isEqualTo("application/octet-stream");
    }
}
