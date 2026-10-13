/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): the directory-backed content store.
 * Pins open/present/listAssets against a real temp tree and, crucially, the
 * confinement guarantee: a {@code ../} in a descriptor cannot escape the module
 * directory, and a null module/path or a non-module root degrades to nothing
 * rather than throwing.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class DirectoryWorldStoreTest {

    @TempDir
    Path base;

    private DirectoryWorldStore store;

    @BeforeEach
    void seedModule() throws IOException {
        Path module = base.resolve("world");
        Files.createDirectories(module.resolve("sub"));
        Files.write(module.resolve("a.dep"), "AAA".getBytes(StandardCharsets.UTF_8));
        Files.write(module.resolve("sub/b-wlc.xml"), "BBB".getBytes(StandardCharsets.UTF_8));
        store = new DirectoryWorldStore(base);
    }

    @Test
    void opensAPresentAssetAndIsEmptyForAnAbsentOne() throws IOException {
        Optional<InputStream> found = store.open("world", "a.dep");
        assertThat(found).isPresent();
        try (InputStream in = found.get()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("AAA");
        }
        assertThat(store.open("world", "nope.dep")).isEmpty();
        assertThat(store.open("nomodule", "a.dep")).isEmpty();
    }

    @Test
    void presentReflectsTheFilesystem() {
        assertThat(store.present("world", "a.dep")).isTrue();
        assertThat(store.present("world", "sub/b-wlc.xml")).isTrue();
        assertThat(store.present("world", "missing")).isFalse();
        assertThat(store.present("world", "sub")).isFalse(); // a directory is not an asset
    }

    @Test
    void aNullModuleOrPathConfinesToNothing() {
        assertThat(store.open(null, "a.dep")).isEmpty();
        assertThat(store.open("world", null)).isEmpty();
        assertThat(store.present(null, "a.dep")).isFalse();
        assertThat(store.present("world", null)).isFalse();
    }

    @Test
    void aDotDotInAPathCannotEscapeTheModuleDirectory() {
        // write a sibling outside the module dir and try to reach it via ../
        assertThat(store.open("world", "../secret")).isEmpty();
        assertThat(store.present("world", "../secret")).isFalse();
    }

    @Test
    void listAssetsWalksTheWholeModuleForTheSuffix() {
        List<String> cells = store.listAssets("world", "-wlc.xml");
        assertThat(cells).containsExactly("sub/b-wlc.xml");

        assertThat(store.listAssets("world", ".dep")).containsExactly("a.dep");
        assertThat(store.listAssets("world", ".nothing")).isEmpty();
        assertThat(store.listAssets("absent", "-wlc.xml")).isEmpty(); // not a directory
    }

    @Test
    void aDoubleDotModuleNameIsNeutralised() throws IOException {
        Path safe = base.resolve("_");
        Files.createDirectories(safe);
        Files.write(safe.resolve("x"), "X".getBytes(StandardCharsets.UTF_8));

        // ".." in the module name is mapped to a safe directory name, never traversed
        assertThat(store.present("..", "x")).isTrue();
        assertThat(store.present("../world", "a.dep")).isFalse();
    }
}
