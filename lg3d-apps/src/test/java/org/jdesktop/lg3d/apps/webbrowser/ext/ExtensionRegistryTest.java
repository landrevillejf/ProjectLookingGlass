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
package org.jdesktop.lg3d.apps.webbrowser.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.jdesktop.lg3d.apps.webbrowser.BrowserStore;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionRegistry.LoadedExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ExtensionRegistry}: classpath built-in discovery via
 * {@link java.util.ServiceLoader}, third-party jar discovery from a temp
 * directory through a child {@link java.net.URLClassLoader}, the permission gate
 * (a third-party extension starts disabled with nothing granted) and the
 * enable/grant persistence round-trip through {@link BrowserStore}.
 */
class ExtensionRegistryTest {

    private static final String SERVICE_FILE =
            "META-INF/services/org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension";

    @Test
    @DisplayName("built-ins are discovered from the classpath, enabled and pre-granted")
    void discoversBuiltins(@TempDir Path config) {
        ExtensionRegistry registry =
                new ExtensionRegistry(new BrowserStore(config), config.resolve("ext"));
        registry.scan();

        LoadedExtension popup = find(registry, "lg3d.popup-blocker");
        assertNotNull(popup, "the popup-blocker built-in is registered via META-INF/services");
        assertTrue(popup.isBuiltin());
        assertTrue(popup.isEnabled(), "built-ins start enabled");
        assertTrue(popup.has(Permission.POPUP), "built-ins are pre-granted their declared perms");

        assertNotNull(find(registry, "lg3d.tracker-blocker"));
        assertNotNull(find(registry, "lg3d.https-upgrade"));
    }

    @Test
    @DisplayName("a temp-dir jar is discovered but gated: disabled, nothing granted")
    void discoversThirdPartyJarGated(@TempDir Path config) throws IOException {
        Path extDir = config.resolve("ext");
        Files.createDirectories(extDir);
        writeExtensionJar(extDir.resolve("sample.jar"),
                List.of(SampleThirdPartyExtension.class.getName()));

        ExtensionRegistry registry = new ExtensionRegistry(new BrowserStore(config), extDir);
        registry.scan();

        LoadedExtension sample = find(registry, SampleThirdPartyExtension.ID);
        assertNotNull(sample, "the drop-in jar's extension is discovered");
        assertFalse(sample.isBuiltin());
        assertFalse(sample.isEnabled(), "a third-party extension starts disabled (the gate)");
        assertTrue(sample.getGranted().isEmpty(), "nothing is granted before approval");
        assertFalse(sample.has(Permission.NAVIGATE));
        assertEquals(extDir.resolve("sample.jar").toString(), sample.getSourcePath());
    }

    @Test
    @DisplayName("enabling a gated extension grants its declared perms and persists across rescans")
    void enableGrantsAndPersists(@TempDir Path config) throws IOException {
        Path extDir = config.resolve("ext");
        Files.createDirectories(extDir);
        writeExtensionJar(extDir.resolve("sample.jar"),
                List.of(SampleThirdPartyExtension.class.getName()));
        BrowserStore store = new BrowserStore(config);

        ExtensionRegistry first = new ExtensionRegistry(store, extDir);
        first.scan();
        first.setEnabled(SampleThirdPartyExtension.ID, true);

        LoadedExtension enabled = find(first, SampleThirdPartyExtension.ID);
        assertTrue(enabled.isEnabled());
        assertTrue(enabled.has(Permission.NAVIGATE), "enabling grants the declared permissions");
        assertTrue(enabled.has(Permission.TOOLBAR));
        assertTrue(Files.isRegularFile(config.resolve("extensions.json")),
                "the decision is written to extensions.json");

        // A fresh registry over the same profile restores the approval.
        ExtensionRegistry second = new ExtensionRegistry(store, extDir);
        second.scan();
        LoadedExtension restored = find(second, SampleThirdPartyExtension.ID);
        assertTrue(restored.isEnabled(), "the enabled flag survives a rescan");
        assertTrue(restored.has(Permission.NAVIGATE), "the grants survive a rescan");
    }

    @Test
    @DisplayName("disabling a built-in persists as disabled")
    void disableBuiltinPersists(@TempDir Path config) {
        BrowserStore store = new BrowserStore(config);
        ExtensionRegistry first = new ExtensionRegistry(store, config.resolve("ext"));
        first.scan();
        first.setEnabled("lg3d.popup-blocker", false);
        assertFalse(find(first, "lg3d.popup-blocker").isEnabled());

        ExtensionRegistry second = new ExtensionRegistry(store, config.resolve("ext"));
        second.scan();
        assertFalse(find(second, "lg3d.popup-blocker").isEnabled());
    }

    @Test
    @DisplayName("grant() replaces the granted set explicitly and persists")
    void grantReplacesSet(@TempDir Path config) {
        BrowserStore store = new BrowserStore(config);
        ExtensionRegistry registry = new ExtensionRegistry(store, config.resolve("ext"));
        registry.scan();
        registry.grant("lg3d.tracker-blocker", java.util.EnumSet.of(Permission.NAVIGATE));
        LoadedExtension le = find(registry, "lg3d.tracker-blocker");
        assertTrue(le.has(Permission.NAVIGATE));
        assertFalse(le.has(Permission.CONTENT_SCRIPT), "an explicit grant drops the others");
    }

    @Test
    @DisplayName("scanning an empty/absent extensions dir is not an error")
    void emptyDirIsFine(@TempDir Path config) {
        ExtensionRegistry registry =
                new ExtensionRegistry(new BrowserStore(config), config.resolve("does-not-exist"));
        registry.scan();
        assertTrue(registry.extensions().size() >= 3, "built-ins are still discovered");
    }

    // ------------------------------------------------------------------

    private static LoadedExtension find(ExtensionRegistry registry, String id) {
        for (LoadedExtension le : registry.extensions()) {
            if (le.getManifest().getId().equals(id)) {
                return le;
            }
        }
        return null;
    }

    /** Writes a jar containing only a {@code META-INF/services} entry for the SPI. */
    private static void writeExtensionJar(Path jar, List<String> implClassNames) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry(SERVICE_FILE));
            out.write((String.join("\n", implClassNames) + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
