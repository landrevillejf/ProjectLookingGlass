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
package org.jdesktop.lg3d.apps.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link BackupStore}: the profile save/load round-trip, the
 * defensive reads (a missing or corrupt file yields an empty list rather than
 * throwing), and the {@code lg3d.backup.dir} override used to point the store at
 * a temp folder.
 */
class BackupStoreTest {

    @TempDir
    Path dir;

    @AfterEach
    void clearOverride() {
        System.clearProperty(BackupStore.DIR_PROPERTY);
    }

    private BackupProfile sample(String name) {
        BackupProfile p = new BackupProfile(name, "/tmp/dest", name.toLowerCase());
        p.getSources().add("/tmp/dest/data");
        p.setExcludePatternsFromCsv("*.log, *.tmp");
        p.setCompressionLevel(8);
        p.setIncludeHidden(true);
        p.setTimestampArchiveName(false);
        return p;
    }

    @Test
    @DisplayName("saving then loading returns the same profiles")
    void roundTrip() {
        BackupStore store = new BackupStore(dir);
        List<BackupProfile> profiles = new ArrayList<>();
        profiles.add(sample("Home"));
        profiles.add(sample("Projects"));
        store.saveProfiles(profiles);

        BackupStore reloaded = new BackupStore(dir);
        List<BackupProfile> back = reloaded.loadProfiles();
        assertEquals(2, back.size());
        assertEquals("Home", back.get(0).getName());
        assertEquals("Projects", back.get(1).getName());
        assertEquals(8, back.get(0).getCompressionLevel());
        assertTrue(back.get(0).isIncludeHidden());
        assertEquals(List.of("*.log", "*.tmp"), back.get(0).getExcludePatterns());
        assertEquals(List.of("/tmp/dest/data"), back.get(0).getSources());
    }

    @Test
    @DisplayName("loading with no file yet yields an empty list")
    void missingFileIsEmpty() {
        BackupStore store = new BackupStore(dir.resolve("not-created"));
        assertTrue(store.loadProfiles().isEmpty());
    }

    @Test
    @DisplayName("a corrupt profiles file is tolerated and read as empty")
    void corruptFileIsEmpty() throws Exception {
        Files.createDirectories(dir);
        Files.write(dir.resolve(BackupStore.PROFILES_FILE),
                "{ this is not valid json ]".getBytes(StandardCharsets.UTF_8));
        BackupStore store = new BackupStore(dir);
        assertTrue(store.loadProfiles().isEmpty(),
                "a damaged config must never stop the app from opening");
    }

    @Test
    @DisplayName("saving null persists an empty list")
    void saveNullIsEmpty() {
        BackupStore store = new BackupStore(dir);
        store.saveProfiles(null);
        assertTrue(new BackupStore(dir).loadProfiles().isEmpty());
    }

    @Test
    @DisplayName("the store creates its directory on first write")
    void createsDirectoryOnWrite() {
        Path nested = dir.resolve("a/b/c");
        BackupStore store = new BackupStore(nested);
        store.saveProfiles(List.of(sample("X")));
        assertTrue(Files.isDirectory(nested));
        assertEquals(1, new BackupStore(nested).loadProfiles().size());
    }

    @Test
    @DisplayName("getConfigDir reports the root the store uses")
    void configDirIsReported() {
        assertEquals(dir, new BackupStore(dir).getConfigDir());
    }

    @Test
    @DisplayName("the dir system property overrides the default location")
    void dirPropertyOverridesDefault() {
        System.setProperty(BackupStore.DIR_PROPERTY, dir.toString());
        assertEquals(dir, BackupStore.defaultConfigDir());
    }

    @Test
    @DisplayName("the default location is ~/.lg3d/backup when unset")
    void defaultLocation() {
        System.clearProperty(BackupStore.DIR_PROPERTY);
        Path expected = Path.of(System.getProperty("user.home"), ".lg3d", "backup");
        assertEquals(expected, BackupStore.defaultConfigDir());
    }

    @Test
    @DisplayName("the no-arg constructor honours the override property")
    void noArgConstructorUsesOverride() {
        System.setProperty(BackupStore.DIR_PROPERTY, dir.toString());
        BackupStore store = new BackupStore();
        store.saveProfiles(List.of(sample("Override")));
        assertEquals(1, new BackupStore(dir).loadProfiles().size());
    }
}
