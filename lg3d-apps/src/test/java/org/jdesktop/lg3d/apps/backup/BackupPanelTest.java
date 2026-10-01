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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless unit tests for {@link BackupPanel}: the profile-list model (seed,
 * add, duplicate, delete), source management, editor population and write-back,
 * and that every mutation is persisted through the {@link BackupStore}.
 *
 * <p>The panel builds entirely from standard Swing components with fixed
 * preferred sizes and creates its {@code JFileChooser}s lazily, so it can be
 * instantiated under {@code java.awt.headless=true}; these tests exercise the
 * model wiring without ever realizing a peer.</p>
 */
class BackupPanelTest {

    @TempDir
    Path dir;

    private BackupPanel newPanel() {
        return new BackupPanel(new BackupStore(dir), new BackupEngine());
    }

    @Test
    @DisplayName("a fresh panel seeds one default profile and reports Ready")
    void constructsAndSeeds() {
        BackupPanel panel = newPanel();
        assertTrue(panel.profileCount() >= 1, "an empty store seeds a default profile");
        assertNotNull(panel.selectedProfile());
        assertEquals("Ready", panel.statusText());
    }

    @Test
    @DisplayName("loading saved profiles populates the list")
    void loadsSavedProfiles() {
        BackupStore store = new BackupStore(dir);
        BackupProfile a = new BackupProfile("Alpha", "/tmp", "a");
        BackupProfile b = new BackupProfile("Beta", "/tmp", "b");
        store.saveProfiles(List.of(a, b));

        BackupPanel panel = new BackupPanel(store, new BackupEngine());
        assertEquals(2, panel.profileCount());
        assertEquals("Alpha", panel.profileAt(0).getName());
        assertEquals("Beta", panel.profileAt(1).getName());
    }

    @Test
    @DisplayName("New adds and selects a profile")
    void newProfileAddsAndSelects() {
        BackupPanel panel = newPanel();
        int before = panel.profileCount();
        panel.newProfile();
        assertEquals(before + 1, panel.profileCount());
        assertEquals("New Backup", panel.selectedProfile().getName());
    }

    @Test
    @DisplayName("deleting the selected profile removes it and reselects")
    void deleteRemovesProfile() {
        BackupPanel panel = newPanel();
        panel.newProfile();
        panel.newProfile();
        int before = panel.profileCount();
        panel.selectProfile(0);
        panel.deleteSelectedProfile();
        assertEquals(before - 1, panel.profileCount());
        assertNotNull(panel.selectedProfile());
    }

    @Test
    @DisplayName("deleting the last profile re-seeds a default so one remains")
    void deleteLastReSeeds() {
        BackupPanel panel = newPanel();
        while (panel.profileCount() > 1) {
            panel.selectProfile(panel.profileCount() - 1);
            panel.deleteSelectedProfile();
        }
        panel.selectProfile(0);
        panel.deleteSelectedProfile();
        assertEquals(1, panel.profileCount(), "the list is never left empty");
        assertNotNull(panel.selectedProfile());
    }

    @Test
    @DisplayName("addSource appends to the selected profile and its source list")
    void addSourceUpdatesProfile() {
        BackupPanel panel = newPanel();
        panel.newProfile();  // a fresh profile whose source list starts empty
        panel.addSource("/tmp/one");
        panel.addSource("/tmp/two");
        assertEquals(2, panel.sourceCount());
        assertEquals("/tmp/one", panel.sourceAt(0));
        assertTrue(panel.selectedProfile().getSources().contains("/tmp/two"));
    }

    @Test
    @DisplayName("addSource ignores blanks and de-duplicates")
    void addSourceDedupes() {
        BackupPanel panel = newPanel();
        panel.newProfile();  // a fresh profile whose source list starts empty
        panel.addSource("/tmp/dup");
        panel.addSource("/tmp/dup");
        panel.addSource("  ");
        panel.addSource(null);
        assertEquals(1, panel.sourceCount());
    }

    @Test
    @DisplayName("selecting a profile repopulates the editor fields")
    void selectionPopulatesEditor() {
        BackupPanel panel = newPanel();
        panel.newProfile();
        panel.selectProfile(0);
        panel.nameField().setText("First");
        panel.selectProfile(1);
        assertEquals("New Backup", panel.nameField().getText());
        panel.selectProfile(0);
        assertEquals("First", panel.nameField().getText());
    }

    @Test
    @DisplayName("editing the name field writes back to the selected profile")
    void editorWriteBack() {
        BackupPanel panel = newPanel();
        panel.selectProfile(0);
        panel.nameField().setText("Renamed");
        assertEquals("Renamed", panel.profileAt(0).getName());
    }

    @Test
    @DisplayName("mutations are persisted and reloadable from the store")
    void mutationsPersist() {
        BackupStore store = new BackupStore(dir);
        BackupPanel panel = new BackupPanel(store, new BackupEngine());
        panel.selectProfile(0);
        panel.nameField().setText("Persisted");
        panel.addSource("/tmp/persist");

        List<BackupProfile> saved = new BackupStore(dir).loadProfiles();
        assertFalse(saved.isEmpty());
        BackupProfile p = saved.get(0);
        assertEquals("Persisted", p.getName());
        assertTrue(p.getSources().contains("/tmp/persist"));
    }

    @Test
    @DisplayName("the panel exposes the engine it was built with")
    void engineAccessor() {
        BackupEngine engine = new BackupEngine();
        BackupPanel panel = new BackupPanel(new BackupStore(dir), engine);
        assertSame(engine, panel.engine());
    }

    @Test
    @DisplayName("setOnClose accepts a callback without throwing")
    void setOnCloseIsSafe() {
        BackupPanel panel = newPanel();
        panel.setOnClose(() -> { /* no-op */ });
        panel.setOnClose(null);
        assertNotNull(panel);
    }
}
