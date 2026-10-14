/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server (world library): the catalog of worlds the server can serve.
 *
 * "Access to every existing world" is a discovery problem before it is a loading
 * one: Open Wonderland ships a LIBRARY of demo worlds (almostempty, auditorium,
 * combined, gallery, japanesepavilion, meetingcomplex, town) as world module jars
 * inside the 400 MB installer, and this port adds its own clean-room outpatient
 * clinic. The seeder loads ONE world by name; the catalog is what answers "which
 * worlds are there, and what are they called" so a deployment (and the desktop's
 * Wonderland World browser) can pick among them instead of knowing one hard-coded
 * module.
 *
 * Two origins, one view:
 *   - BUNDLED worlds live on the classpath and are read through
 *     {@link ManifestClasspathWorldStore}; the outpatient clinic ships in the jar,
 *     so it is always available. The list is a constant because a classpath jar
 *     cannot be enumerated -- exactly the limitation ManifestClasspathWorldStore
 *     exists to work around for a single known module, not for discovery.
 *   - INSTALLED worlds are {@code *-world.jar} files in the configured
 *     {@code wonderland.world.dir}, read through {@link JarWorldStore}; dropping
 *     a stock world jar (extracted from the installer) into that directory makes
 *     it appear here with no rebuild.
 *
 * Every read is defensive: a jar that is not a world, a module.xml that will not
 * parse, an unreadable cell file -- none of them may throw or drop the whole
 * catalog, because a listing is a courtesy surface, not a boot dependency. A
 * degraded entry falls back to the directory/jar name as its title.
 */
package org.jdesktop.wonderland.server.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jdesktop.wonderland.modules.asset.JarWorldStore;
import org.jdesktop.wonderland.modules.asset.ManifestClasspathWorldStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Discovers the worlds the server can serve: bundled classpath worlds + installed jars. */
@Component
public class WorldCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(WorldCatalog.class);

    /** The suffix that makes a classpath cell file a world cell. */
    static final String CELL_SUFFIX = "-wlc.xml";

    /**
     * The worlds bundled on the classpath. The clinic ships in the server jar, so
     * it is always offered; a classpath jar cannot be enumerated, hence the fixed
     * list (see the header). Add a bundled world by adding its module name here and
     * shipping its resources under {@code src/main/resources/<module>/}.
     */
    static final List<String> BUNDLED = List.of(WorldSeeder.CLINIC_MODULE);

    private static final Pattern NAME = Pattern.compile("<name>\\s*(.*?)\\s*</name>", Pattern.DOTALL);
    private static final Pattern DESCRIPTION =
            Pattern.compile("<description>\\s*(.*?)\\s*</description>", Pattern.DOTALL);

    private final Path worldsDir;

    public WorldCatalog(@Value("${wonderland.world.dir:}") String worldsDir) {
        this.worldsDir = worldsDir == null || worldsDir.isBlank()
                ? null : Path.of(worldsDir.trim()).toAbsolutePath().normalize();
    }

    /** Every world the server can serve: bundled first, then installed jars by name. */
    public List<WorldSummary> available() {
        List<WorldSummary> out = new ArrayList<>();
        for (String module : BUNDLED) {
            out.add(bundledSummary(module));
        }
        for (Path jar : worldJars()) {
            out.add(jarSummary(jar));
        }
        out.sort(Comparator.comparing(WorldSummary::source)
                .thenComparing(WorldSummary::module));
        return out;
    }

    /** The installed world jar for {@code module}, if the worlds directory carries one. */
    public Optional<Path> worldJar(String module) {
        if (module == null) {
            return Optional.empty();
        }
        return worldJars().stream()
                .filter(j -> module.equals(stem(j)))
                .findFirst();
    }

    /** The directory's world jars, sorted; empty when no worlds directory is configured. */
    List<Path> worldJars() {
        if (worldsDir == null || !Files.isDirectory(worldsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(worldsDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> stem(p)))
                    .toList();
        } catch (IOException ex) {
            LOG.warn("cannot list worlds directory {}: {}", worldsDir, ex.toString());
            return List.of();
        }
    }

    private WorldSummary bundledSummary(String module) {
        ManifestClasspathWorldStore store = new ManifestClasspathWorldStore();
        int cells = store.listAssets(module, CELL_SUFFIX).size();
        String[] title = readModuleXml(store::open, module);
        return new WorldSummary(module, title[0], title[1],
                WorldSummary.SOURCE_CLASSPATH, cells);
    }

    private WorldSummary jarSummary(Path jar) {
        String module = stem(jar);
        int cells = 0;
        String[] title = new String[] {module, ""};
        try (JarWorldStore store = new JarWorldStore(jar)) {
            cells = store.listAssets(module, CELL_SUFFIX).size();
            title = readModuleXml(store::open, module);
        } catch (IOException | RuntimeException ex) {
            LOG.warn("world jar {} is not readable as a world ({}); listing it by name only",
                    jar.getFileName(), ex.toString());
        }
        return new WorldSummary(module, title[0], title[1], WorldSummary.SOURCE_JAR, cells);
    }

    /**
     * Read the {@code <name>} and {@code <description>} out of a module's
     * {@code module.xml} through any store's {@code open}. Degrades to
     * {module, ""} when module.xml is absent or unreadable -- the module name is a
     * fine title, and a broken description is not worth losing the row over.
     */
    private static String[] readModuleXml(OpenFunction open, String module) {
        try (InputStream in = open.open(module, "module.xml").orElse(null)) {
            if (in == null) {
                return new String[] {module, ""};
            }
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String name = first(NAME, xml);
            String desc = first(DESCRIPTION, xml);
            return new String[] {name == null ? module : name, desc == null ? "" : desc};
        } catch (IOException | RuntimeException ex) {
            return new String[] {module, ""};
        }
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String stem(Path jar) {
        String name = jar.getFileName().toString();
        return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }

    /** A store's {@code open(module, path)} as a functional seam shared by both origins. */
    @FunctionalInterface
    private interface OpenFunction {
        Optional<InputStream> open(String module, String path);
    }
}
