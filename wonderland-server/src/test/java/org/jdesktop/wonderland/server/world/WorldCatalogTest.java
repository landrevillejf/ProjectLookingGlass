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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(clinic.cellCount()).isEqualTo(9);
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
}
