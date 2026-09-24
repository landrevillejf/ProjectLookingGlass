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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.GroupSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the descriptor reader that feeds the 2D start menu: the two bean kinds
 * it understands, the properties it keeps, group/item resolution and the default
 * scan locations.
 */
class Desktop2DMenuConfigTest {

    /** A descriptor exercising every shape the reader has to cope with. */
    private static final String FIXTURE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<java version=\"1.5.0\" class=\"java.beans.XMLDecoder\">\n"
            + " <object class=\"org.jdesktop.lg3d.scenemanager.utils.startmenu.StartMenuGroupConfig\">\n"
            + "  <void property=\"defaultGroup\"><boolean>true</boolean></void>\n"
            + "  <void property=\"desc\"><string>Main menu group</string></void>\n"
            + "  <void property=\"groupLinks\">\n"
            + "   <array class=\"java.lang.String\" length=\"2\">\n"
            // deliberately out of index order: the reader must sort by index
            + "    <void index=\"1\"><string>Undefined</string></void>\n"
            + "    <void index=\"0\"><string>Utilities</string></void>\n"
            + "   </array>\n"
            + "  </void>\n"
            + "  <void property=\"name\"><string>Main</string></void>\n"
            + " </object>\n"
            + " <object class=\"org.jdesktop.lg3d.scenemanager.utils.startmenu.StartMenuGroupConfig\">\n"
            + "  <void property=\"desc\"><string>Utilities</string></void>\n"
            + "  <void property=\"groupLinks\">\n"
            + "   <array class=\"java.lang.String\" length=\"1\">\n"
            + "    <void index=\"0\"><string>Main</string></void>\n"
            + "   </array>\n"
            + "  </void>\n"
            + "  <void property=\"name\"><string>Utilities</string></void>\n"
            + " </object>\n"
            + " <object class=\"org.jdesktop.lg3d.scenemanager.utils.startmenu.StartMenuItemConfig\">\n"
            + "  <void property=\"command\"><string>java org.jdesktop.lg3d.apps.calculator.Calculator</string></void>\n"
            + "  <void property=\"desc\"><string>A calculator</string></void>\n"
            + "  <void property=\"displayResourceUrlName\">\n"
            + "   <string>resource:///resources/images/icon/calculator.png</string>\n"
            + "  </void>\n"
            + "  <void property=\"menuGroup\"><string>Utilities</string></void>\n"
            + "  <void property=\"name\"><string>Calculator</string></void>\n"
            + " </object>\n"
            + " <object class=\"org.jdesktop.lg3d.scenemanager.utils.startmenu.StartMenuItemConfig\">\n"
            + "  <void property=\"command\"><string>firefox</string></void>\n"
            + "  <void property=\"menuGroup\"><string>Nowhere</string></void>\n"
            + "  <void property=\"name\"><string>Orphan</string></void>\n"
            + " </object>\n"
            // no command: dropped, it could never be launched
            + " <object class=\"org.jdesktop.lg3d.scenemanager.utils.startmenu.StartMenuItemConfig\">\n"
            + "  <void property=\"name\"><string>Commandless</string></void>\n"
            + " </object>\n"
            // a bean kind the reader must ignore
            + " <object class=\"org.jdesktop.lg3d.scenemanager.config.ApplicationDescription\">\n"
            + "  <void property=\"exec\"><string>xterm</string></void>\n"
            + " </object>\n"
            + "</java>\n";

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreProperties() {
        System.clearProperty("lg.etcdir");
    }

    private MenuModel fixtureModel() throws Exception {
        return Desktop2DMenuConfig.build(List.of(writeFixture().toUri().toURL()));
    }

    private Path writeFixture() throws Exception {
        Path file = tempDir.resolve("fixture.lgcfg");
        Files.write(file, FIXTURE.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    @DisplayName("groups and items are read, other bean kinds ignored")
    void readsGroupsAndItemsOnly() throws Exception {
        MenuModel model = fixtureModel();
        assertEquals(2, model.getGroups().size());
        assertEquals(2, model.getItems().size(), "the commandless item is dropped");
        assertFalse(model.isEmpty());
    }

    @Test
    @DisplayName("the default group is the menu root")
    void rootGroupIsTheDefaultGroup() throws Exception {
        MenuModel model = fixtureModel();
        GroupSpec root = model.getRootGroup();
        assertNotNull(root);
        assertEquals("Main", root.getName());
        assertEquals("Main menu group", root.getDesc());
        assertTrue(root.isDefaultGroup());
    }

    @Test
    @DisplayName("group links keep their declared (index) order")
    void groupLinksKeepIndexOrder() throws Exception {
        GroupSpec root = fixtureModel().getRootGroup();
        assertEquals(List.of("Utilities", "Undefined"), root.getLinks());
    }

    @Test
    @DisplayName("links to undefined groups are skipped when resolved")
    void linkedGroupsResolveOnlyKnownNames() throws Exception {
        MenuModel model = fixtureModel();
        List<GroupSpec> children = model.getLinkedGroups(model.getRootGroup());
        assertEquals(1, children.size());
        assertEquals("Utilities", children.get(0).getName());
        assertSame(model.getGroup("Utilities"), children.get(0));
        assertNull(model.getGroup("Undefined"));
        assertTrue(model.getLinkedGroups(null).isEmpty());
    }

    @Test
    @DisplayName("item properties, including the icon path, are kept")
    void itemPropertiesAreRead() throws Exception {
        MenuModel model = fixtureModel();
        List<ItemSpec> utilities = model.getItemsOf("Utilities");
        assertEquals(1, utilities.size());
        ItemSpec calculator = utilities.get(0);
        assertEquals("Calculator", calculator.getName());
        assertEquals("java org.jdesktop.lg3d.apps.calculator.Calculator",
                calculator.getCommand());
        assertEquals("A calculator", calculator.getDesc());
        assertEquals("Utilities", calculator.getMenuGroup());
        assertEquals("resources/images/icon/calculator.png",
                calculator.getIconResource());
        assertTrue(calculator.toString().contains("Calculator"));
    }

    @Test
    @DisplayName("items in an undefined group are reported as orphans")
    void orphanItemsAreCollected() throws Exception {
        MenuModel model = fixtureModel();
        List<ItemSpec> orphans = model.getOrphanItems();
        assertEquals(1, orphans.size());
        assertEquals("Orphan", orphans.get(0).getName());
        assertNull(orphans.get(0).getIconResource());
        // The group it names is not defined, which is what makes it an orphan.
        assertNull(model.getGroup("Nowhere"));
    }

    @Test
    @DisplayName("the resource:/// scheme is stripped from icon locations")
    void stripResourceScheme() {
        assertEquals("resources/images/icon/x.png",
                Desktop2DMenuConfig.stripResourceScheme(
                        "resource:///resources/images/icon/x.png"));
        assertEquals("org/jdesktop/lg3d/apps/x.png",
                Desktop2DMenuConfig.stripResourceScheme(
                        "resource:///org/jdesktop/lg3d/apps/x.png"));
        assertEquals("resources/images/icon/x.png",
                Desktop2DMenuConfig.stripResourceScheme(
                        "resources/images/icon/x.png"));
        assertNull(Desktop2DMenuConfig.stripResourceScheme(null));
        assertNull(Desktop2DMenuConfig.stripResourceScheme("  "));
        assertNull(Desktop2DMenuConfig.stripResourceScheme("resource:///"));
    }

    @Test
    @DisplayName("the 3D marker is dropped from apps the 2D desktop runs")
    void displayNameStrips3dForRunnableApps() {
        // A PANEL app: hosted as a plain Swing internal frame, never a 3D window.
        assertEquals("Mail",
                Desktop2DMenuConfig.desktopDisplayName("Mail 3D",
                        "java org.jdesktop.lg3d.apps.mail.Mail3D"));
        assertEquals("Chess",
                Desktop2DMenuConfig.desktopDisplayName("Chess 3D",
                        "java org.jdesktop.lg3d.apps.games.chess.Chess3D"));
        // An EXTERNAL app runs in 2D too, so its marker is dropped as well.
        assertEquals("Browser",
                Desktop2DMenuConfig.desktopDisplayName("3D Browser", "firefox"));
    }

    @Test
    @DisplayName("a pure-3D app the 2D desktop cannot run keeps its 3D name")
    void displayNameKeeps3dForUnavailableApps() {
        assertEquals("ArchViz3D",
                Desktop2DMenuConfig.desktopDisplayName("ArchViz3D",
                        "java org.jdesktop.lg3d.apps.archviz3d.ArchViz3D"));
        // A commandless item is UNAVAILABLE and keeps its name.
        assertEquals("Mail 3D",
                Desktop2DMenuConfig.desktopDisplayName("Mail 3D", null));
    }

    @Test
    @DisplayName("strip3dMarker handles leading, trailing and attached forms")
    void strip3dMarkerPositions() {
        assertEquals("Mail", Desktop2DMenuConfig.strip3dMarker("Mail 3D"));
        assertEquals("Tic-Tac-Toe",
                Desktop2DMenuConfig.strip3dMarker("Tic-Tac-Toe 3D"));
        assertEquals("Browser", Desktop2DMenuConfig.strip3dMarker("3D Browser"));
        assertEquals("PeriodicTable",
                Desktop2DMenuConfig.strip3dMarker("PeriodicTable3D"));
        // An interior marker is left alone; the name is not just "3D"-hunting.
        assertEquals("K-Web 3D UI Demo",
                Desktop2DMenuConfig.strip3dMarker("K-Web 3D UI Demo"));
        assertEquals("Lg3d Homepage",
                Desktop2DMenuConfig.strip3dMarker("Lg3d Homepage"));
        // A name that is nothing but the marker is returned unchanged.
        assertEquals("3D", Desktop2DMenuConfig.strip3dMarker("3D"));
        // Surrounding whitespace is trimmed.
        assertEquals("Mail", Desktop2DMenuConfig.strip3dMarker("  Mail 3D  "));
    }

    @Test
    @DisplayName("unreadable descriptors are skipped instead of failing the menu")
    void badDescriptorsAreSkipped() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(java.net.URI.create("file:///definitely/not/here.lgcfg").toURL());
        Path broken = tempDir.resolve("broken.lgcfg");
        Files.write(broken, "<not-xml".getBytes(StandardCharsets.UTF_8));
        urls.add(broken.toUri().toURL());
        urls.add(writeFixture().toUri().toURL());

        MenuModel model = Desktop2DMenuConfig.build(urls);
        assertEquals(2, model.getGroups().size(), "the readable one still loads");
    }

    @Test
    @DisplayName("an empty descriptor list yields an empty model")
    void emptyModel() {
        MenuModel model = Desktop2DMenuConfig.build(List.of());
        assertTrue(model.isEmpty());
        assertNull(model.getRootGroup());
        assertTrue(model.getGroups().isEmpty());
        assertTrue(model.getItems().isEmpty());
        assertTrue(model.getOrphanItems().isEmpty());
    }

    @Test
    @DisplayName("the root group falls back to Main, then to the first group")
    void rootGroupFallbacks() {
        MenuModel noDefaults = Desktop2DMenuConfig.build(List.of());
        assertNull(noDefaults.getRootGroup());

        MenuModel named = new MenuModel(
                List.of(new GroupSpec("Other", null, List.of(), false),
                        new GroupSpec("Main", null, List.of(), false)),
                List.of());
        assertEquals("Main", named.getRootGroup().getName());

        MenuModel firstWins = new MenuModel(
                List.of(new GroupSpec("Other", null, List.of(), false)),
                List.of());
        assertEquals("Other", firstWins.getRootGroup().getName());
    }

    @Test
    @DisplayName("lg.etcdir/lg3d is the first descriptor location scanned")
    void scansTheEtcDir() throws Exception {
        Path etc = tempDir.resolve("etc");
        Path lg3d = etc.resolve("lg3d");
        Files.createDirectories(lg3d);
        Path descriptor = lg3d.resolve("startmenu.lgcfg");
        Files.write(descriptor, FIXTURE.getBytes(StandardCharsets.UTF_8));
        // A non-descriptor file must be ignored.
        Files.write(lg3d.resolve("logging.properties"),
                "handlers=".getBytes(StandardCharsets.UTF_8));

        System.setProperty("lg.etcdir", etc.toString() + "/");
        List<URL> urls = Desktop2DMenuConfig.defaultConfigUrls();
        assertTrue(urls.stream().anyMatch(u -> u.toString().endsWith("startmenu.lgcfg")),
                "expected the etc-dir descriptor in " + urls);

        MenuModel model = Desktop2DMenuConfig.load();
        assertEquals("Main", model.getRootGroup().getName());
    }

    @Test
    @DisplayName("lg.etcdir may point straight at the lg3d directory")
    void etcDirMayBeTheLg3dDirItself() throws Exception {
        Path lg3d = tempDir.resolve("plain-etc");
        Files.createDirectories(lg3d);
        Files.write(lg3d.resolve("menu.lgcfg"), FIXTURE.getBytes(StandardCharsets.UTF_8));

        System.setProperty("lg.etcdir", lg3d.toString());
        List<URL> urls = Desktop2DMenuConfig.defaultConfigUrls();
        assertTrue(urls.stream().anyMatch(u -> u.toString().endsWith("menu.lgcfg")),
                "expected the descriptor in " + urls);
    }

    @Test
    @DisplayName("a missing or unset lg.etcdir is not an error")
    void missingEtcDirIsTolerated() {
        System.clearProperty("lg.etcdir");
        assertNotNull(Desktop2DMenuConfig.defaultConfigUrls());

        System.setProperty("lg.etcdir", tempDir.resolve("nope").toString());
        assertNotNull(Desktop2DMenuConfig.defaultConfigUrls());
    }
}
