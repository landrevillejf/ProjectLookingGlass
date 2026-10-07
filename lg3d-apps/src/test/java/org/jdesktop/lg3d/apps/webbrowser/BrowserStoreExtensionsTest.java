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
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the {@code extensions.json} persistence added to
 * {@link BrowserStore}: a round-trip plus the same defensive reads the other
 * profile files get (a missing or corrupt file yields an empty list, never an
 * exception).
 */
class BrowserStoreExtensionsTest {

    private static ExtensionState state(String id, boolean enabled, String... perms) {
        ExtensionState s = new ExtensionState();
        s.setId(id);
        s.setEnabled(enabled);
        s.setGrantedPermissions(new ArrayList<>(List.of(perms)));
        s.setSource("");
        return s;
    }

    @Test
    @DisplayName("extension states round-trip through extensions.json")
    void roundTrip(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        store.saveExtensionStates(List.of(
                state("lg3d.popup-blocker", true, "POPUP"),
                state("test.sample", false, "NAVIGATE", "TOOLBAR")));

        List<ExtensionState> loaded = store.loadExtensionStates();
        assertEquals(2, loaded.size());
        assertEquals("lg3d.popup-blocker", loaded.get(0).getId());
        assertTrue(loaded.get(0).isEnabled());
        assertEquals(List.of("POPUP"), loaded.get(0).getGrantedPermissions());
        assertFalse(loaded.get(1).isEnabled());
        assertEquals(List.of("NAVIGATE", "TOOLBAR"), loaded.get(1).getGrantedPermissions());
        assertTrue(Files.isRegularFile(dir.resolve("extensions.json")));
    }

    @Test
    @DisplayName("a missing extensions file loads as an empty list")
    void missingIsEmpty(@TempDir Path dir) {
        assertTrue(new BrowserStore(dir).loadExtensionStates().isEmpty());
    }

    @Test
    @DisplayName("a corrupt extensions file degrades to an empty list")
    void corruptIsEmpty(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("extensions.json"), "[ not valid json ");
        assertTrue(new BrowserStore(dir).loadExtensionStates().isEmpty());
    }

    @Test
    @DisplayName("saving null writes an empty list, not an error")
    void nullSavesEmpty(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        store.saveExtensionStates(null);
        assertTrue(store.loadExtensionStates().isEmpty());
    }
}
