/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link IconPackManager}'s pack selection and single-icon override
 * resolution: bundled discovery off the classpath, an imported pack backed by a
 * temp <em>folder</em> and by a temp <em>zip</em>, base-name and app-name-slug
 * keys, the "only the active pack is consulted" rule, and the fallback to the
 * default pack. A fixture bundled pack ({@code testpack}, two solid-colour PNGs)
 * lives under {@code src/test/resources/resources/images/icon-packs} so real
 * discovery is exercised. Everything is headless; the shared {@link DesktopConfig}
 * singleton is reset after each test so no real user preference is left behind.
 */
class IconPackManagerTest {

    private static final String FIXTURE_PACK = "testpack";

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    /** A tiny 8x8 solid-colour PNG, as bytes (for temp folders and zips). */
    private static byte[] png(Color c) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                img.setRGB(x, y, c.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("available() always leads with the default pack")
    void availableLeadsWithDefault() {
        List<IconPack> packs = IconPackManager.available();
        assertFalse(packs.isEmpty(), "the default pack is always present");
        assertTrue(packs.get(0).isDefault(), "the default pack is first");
        assertEquals(IconPack.DEFAULT_ID, packs.get(0).id());
    }

    @Test
    @DisplayName("bundled discovery finds packs under resources/images/icon-packs")
    void bundledDiscoveryFindsFixturePack() {
        // The test classpath carries a fixture bundled pack under
        // resources/images/icon-packs, so discovery must surface it as BUNDLED.
        List<IconPack> bundled = IconPackManager.bundled();
        IconPack fixture = bundled.stream()
                .filter(p -> FIXTURE_PACK.equals(p.id()))
                .findFirst()
                .orElse(null);
        assertNotNull(fixture, "the fixture bundled pack is discovered: " + bundled);
        assertEquals(IconPack.Source.BUNDLED, fixture.source());
        assertEquals("Testpack", fixture.displayName(), "the id is prettified for display");
        assertTrue(IconPackManager.available().stream().anyMatch(p -> FIXTURE_PACK.equals(p.id())),
                "a discovered bundled pack is selectable");
    }

    @Test
    @DisplayName("an active bundled pack overrides by icon-resource base name")
    void bundledOverrideByBasename() {
        cfg.setIconPack(FIXTURE_PACK);
        Icon icon = IconPackManager.overrideFor("resources/images/icon/chess.png", "Chess", 16);
        assertNotNull(icon, "the fixture pack ships chess.png");
        assertEquals(16, icon.getIconWidth(), "the packed PNG is scaled to the request");
        assertEquals(16, icon.getIconHeight());
    }

    @Test
    @DisplayName("an active pack overrides by app-name slug when there is no descriptor art")
    void bundledOverrideBySlug() {
        cfg.setIconPack(FIXTURE_PACK);
        // No iconResource, so the base-name key is unavailable; the slug of the
        // app name ("Mail 3D" -> "mail-3d") must still match the packed PNG.
        Icon icon = IconPackManager.overrideFor(null, "Mail 3D", 24);
        assertNotNull(icon, "the fixture pack ships mail-3d.png");
        assertEquals(24, icon.getIconWidth());
    }

    @Test
    @DisplayName("the default pack overrides nothing")
    void defaultOverridesNothing() {
        cfg.resetToDefaults();
        assertTrue(IconPackManager.active().isDefault());
        assertNull(IconPackManager.overrideFor("resources/images/icon/chess.png", "Chess", 16));
    }

    @Test
    @DisplayName("active() selects the persisted pack, else falls back to default")
    void activeSelectionAndFallback() {
        cfg.setIconPack(FIXTURE_PACK);
        assertEquals(FIXTURE_PACK, IconPackManager.active().id());
        cfg.setIconPack("no-such-pack");
        assertTrue(IconPackManager.active().isDefault(),
                "a pack that is no longer available degrades to the default");
    }

    @Test
    @DisplayName("an imported folder is discovered and resolves by base name")
    void importedFolderResolves(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("chess.png"), png(Color.RED));
        cfg.setIconPackDir(dir.toString());
        cfg.setIconPack(IconPack.IMPORTED_ID);

        IconPack imported = IconPackManager.imported();
        assertNotNull(imported, "a valid import path yields an imported pack");
        assertEquals(IconPack.IMPORTED_ID, imported.id());
        assertEquals(IconPack.Source.IMPORTED, imported.source());
        assertTrue(IconPackManager.available().stream().anyMatch(p -> IconPack.IMPORTED_ID.equals(p.id())),
                "the imported pack is selectable");

        Icon icon = IconPackManager.overrideFor("resources/images/icon/chess.png", "Chess", 16);
        assertNotNull(icon, "the imported folder carries chess.png");
        assertEquals(16, icon.getIconWidth());
    }

    @Test
    @DisplayName("an imported zip resolves an entry by base name")
    void importedZipResolves(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("pack.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("mail-3d.png"));
            zos.write(png(Color.BLUE));
            zos.closeEntry();
        }
        cfg.setIconPackDir(zip.toString());
        cfg.setIconPack(IconPack.IMPORTED_ID);

        assertNotNull(IconPackManager.imported(), "a zip path is a valid import");
        Icon icon = IconPackManager.overrideFor(null, "Mail 3D", 22);
        assertNotNull(icon, "the zip carries mail-3d.png (slug of 'Mail 3D')");
        assertEquals(22, icon.getIconWidth());
    }

    @Test
    @DisplayName("only the active pack is consulted; an import does not fall through to bundled")
    void activePackIsExclusive(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("solitaire.png"), png(Color.GREEN));
        cfg.setIconPackDir(dir.toString());
        cfg.setIconPack(IconPack.IMPORTED_ID);
        // The imported pack carries solitaire.png but not chess.png; the bundled
        // fixture carries chess.png. Only the ACTIVE pack resolves, so chess is
        // null while solitaire resolves.
        assertNull(IconPackManager.overrideFor("resources/images/icon/chess.png", "Chess", 16),
                "the imported pack has no chess.png and bundled packs are not consulted");
        assertNotNull(IconPackManager.overrideFor("resources/images/icon/solitaire.png", "Solitaire", 16));
    }

    @Test
    @DisplayName("a missing import path yields no imported pack")
    void missingImportPathYieldsNull() {
        cfg.setIconPackDir("/nonexistent/lg3d/pack-" + System.nanoTime());
        assertNull(IconPackManager.imported());
        assertTrue(IconPackManager.available().stream().noneMatch(p -> IconPack.IMPORTED_ID.equals(p.id())),
                "a dangling import path is not offered");
    }

    @Test
    @DisplayName("basename() strips the resource path down to the file name")
    void basenameHelper() {
        assertEquals("chess.png", IconPackManager.basename("resources/images/icon/chess.png"));
        assertEquals("chess.png", IconPackManager.basename("  resources\\images\\icon\\chess.png  "),
                "backslashes and surrounding whitespace are handled");
        assertEquals("chess.png", IconPackManager.basename("chess.png"));
        assertNull(IconPackManager.basename(null));
        assertNull(IconPackManager.basename("   "));
        assertNull(IconPackManager.basename("resources/images/icon/"), "a trailing slash has no base name");
    }

    @Test
    @DisplayName("slug() lower-cases and dash-separates an application name")
    void slugHelper() {
        assertEquals("mail-3d", IconPackManager.slug("Mail 3D"));
        assertEquals("control-center", IconPackManager.slug("  Control Center  "));
        assertEquals("chess", IconPackManager.slug("Chess"));
        assertNull(IconPackManager.slug("!!!"), "punctuation alone leaves no slug");
        assertNull(IconPackManager.slug(null));
    }
}
