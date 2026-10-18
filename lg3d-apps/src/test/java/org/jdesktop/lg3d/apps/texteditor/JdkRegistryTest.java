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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the central JDK manager. Every case runs against a
 * {@code @TempDir} base (never the real {@code ~/.espresso}) and a fake
 * {@link Downloader} that writes canned archives (never the network). The point
 * they pin down is the design contract: JDKs are shared and selected, so a
 * download lands in the store - not in a project - and removing a registered
 * JDK forgets it without touching the user's own folder.
 */
class JdkRegistryTest {

    /** Creates a JDK home on disk: {@code <home>/bin/java} plus a {@code release}. */
    private static Path makeJdkHome(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/bin/sh\n");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return home;
    }

    private static JdkRegistry registry(Path base) {
        return new JdkRegistry(base, (url, dest) -> {
        });
    }

    @Test
    @DisplayName("list() merges managed store folders and registered ones, newest version first")
    void listIndexesManagedAndRegistered(@TempDir Path base) throws Exception {
        Path store = base.resolve("jdks");
        makeJdkHome(store.resolve("jdk-21.0.4+7"), "21.0.4");
        makeJdkHome(store.resolve("jdk-25.0.1+3"), "25.0.1");
        Path external = makeJdkHome(base.resolve("external").resolve("zulu-17"), "17.0.9");

        JdkRegistry reg = registry(base);
        reg.register(external);

        List<JdkEntry> all = reg.list();
        assertEquals(List.of("jdk-25.0.1+3", "jdk-21.0.4+7", "zulu-17"),
                all.stream().map(JdkEntry::id).toList());
        assertTrue(all.get(0).managed(), "a store folder is managed");
        assertFalse(all.get(2).managed(), "a registered folder is not managed");
        assertEquals("25.0.1", all.get(0).version());
    }

    @Test
    @DisplayName("resolve() finds a JDK by exact id, full version or feature number")
    void resolveByIdAndVersion(@TempDir Path base) throws Exception {
        Path home = makeJdkHome(base.resolve("jdks").resolve("jdk-21.0.4+7"), "21.0.4");
        JdkRegistry reg = registry(base);

        assertEquals(Optional.of(home), reg.resolve("jdk-21.0.4+7"));
        assertEquals(Optional.of(home), reg.resolve("21.0.4"));
        assertEquals(Optional.of(home), reg.resolve("21"));
        assertTrue(reg.resolve("99").isEmpty());
        assertTrue(reg.resolve("").isEmpty());
        assertTrue(reg.resolve(null).isEmpty());
    }

    @Test
    @DisplayName("register() refuses a folder that is not a JDK home")
    void registerRejectsNonJdk(@TempDir Path base) throws Exception {
        Path notJdk = Files.createDirectories(base.resolve("plain"));
        Files.writeString(notJdk.resolve("readme.txt"), "hi");
        assertThrows(IOException.class, () -> registry(base).register(notJdk));
    }

    @Test
    @DisplayName("a registered folder persists to jdks.properties and survives a new instance")
    void registerPersistsAcrossInstances(@TempDir Path base) throws Exception {
        Path external = makeJdkHome(base.resolve("ext").resolve("temurin-21"), "21.0.4");
        registry(base).register(external);
        assertTrue(Files.isRegularFile(base.resolve("jdks.properties")));

        JdkRegistry reopened = registry(base);
        assertEquals(1, reopened.list().size());
        assertEquals("temurin-21", reopened.list().get(0).id());
        assertFalse(reopened.list().get(0).managed());
    }

    @Test
    @DisplayName("download() fetches into the shared store - never a project - and marks it managed")
    void downloadLandsInTheSharedStore(@TempDir Path base) throws Exception {
        List<String> urls = new ArrayList<>();
        Downloader fake = (url, dest) -> {
            urls.add(url);
            if (dest.getFileName().toString().endsWith(".zip")) {
                CannedArchives.writeJdkZip(dest, "jdk-25.0.1+3", "25.0.1");
            } else {
                CannedArchives.writeJdkTarGz(dest, "jdk-25.0.1+3", "25.0.1");
            }
        };
        JdkRegistry reg = new JdkRegistry(base, fake);

        JdkEntry entry = reg.download("25", msg -> {
        });

        assertEquals(1, urls.size());
        assertTrue(urls.get(0).startsWith("https://api.adoptium.net/v3/binary/latest/25/ga/"),
                urls.get(0));
        assertTrue(entry.managed());
        assertEquals("jdk-25.0.1+3", entry.id());
        assertEquals("25.0.1", entry.version());
        assertTrue(entry.home().startsWith(base.resolve("jdks")),
                "the JDK landed in the shared store, not inside a project");
        assertTrue(JdkRegistry.isJdkHome(entry.home()));
        try (Stream<Path> kids = Files.list(base.resolve("jdks"))) {
            assertEquals(1, kids.count(), "only the finished JDK remains, no staging leftovers");
        }
    }

    @Test
    @DisplayName("remove() deletes a managed JDK but only forgets a registered folder")
    void removeDeletesManagedButKeepsRegisteredFolder(@TempDir Path base) throws Exception {
        Path store = base.resolve("jdks");
        makeJdkHome(store.resolve("jdk-21.0.4+7"), "21.0.4");
        Path external = makeJdkHome(base.resolve("ext").resolve("zulu-17"), "17.0.9");
        JdkRegistry reg = registry(base);
        reg.register(external);

        reg.remove("jdk-21.0.4+7");
        assertFalse(Files.exists(store.resolve("jdk-21.0.4+7")), "the managed folder is deleted");

        reg.remove("zulu-17");
        assertTrue(Files.isDirectory(external), "the user's own folder is never deleted");
        assertTrue(reg.list().isEmpty());
    }

    @Test
    @DisplayName("jdkUrl() targets the running platform's Adoptium binary endpoint")
    void jdkUrlReflectsPlatform(@TempDir Path base) {
        String url = registry(base).jdkUrl("21");
        assertTrue(url.contains("/latest/21/ga/"), url);
        assertTrue(url.contains("/" + JdkRegistry.osName() + "/"), url);
        assertTrue(url.contains("/" + JdkRegistry.archName() + "/"), url);
        assertTrue(url.endsWith("/jdk/hotspot/normal/eclipse"), url);
    }
}
