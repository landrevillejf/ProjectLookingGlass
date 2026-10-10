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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Component;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.GroupSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Desktop2DStartMenu}'s category-tree assembly: a linked group
 * renders as a sub-menu that carries a category icon (via {@link CategoryIcons})
 * and lists its runnable items, while an empty category is dropped. The popup and
 * its sub-menus are constructed but never shown, so the tests run headless.
 */
class Desktop2DStartMenuTest {

    private static JMenu subMenuNamed(JPopupMenu menu, String name) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenu && name.equals(((JMenu) c).getText())) {
                return (JMenu) c;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a category sub-menu carries an icon and lists its runnable item")
    void categorySubmenuCarriesAnIcon() {
        MenuModel model = new MenuModel(
                List.of(
                        new GroupSpec("Main", null, List.of("Internet"), true),
                        new GroupSpec("Internet", "Internet Tools",
                                List.of("Main"), false)),
                List.of(
                        new ItemSpec("Calculator",
                                "java org.jdesktop.lg3d.apps.calculator.Calculator",
                                "Do sums", "Internet", null)));

        JPopupMenu menu = Desktop2DStartMenu.build(model, item -> { });

        JMenu internet = subMenuNamed(menu, "Internet");
        assertNotNull(internet, "the Internet category renders as a sub-menu");
        assertNotNull(internet.getIcon(), "the category sub-menu carries an icon");
        assertEquals("Internet Tools", internet.getToolTipText(),
                "the group description becomes the tool tip");
        assertEquals(1, internet.getItemCount(), "its runnable item is listed");
    }

    @Test
    @DisplayName("a category with no runnable entry is dropped")
    void emptyCategoryIsSkipped() {
        MenuModel model = new MenuModel(
                List.of(
                        new GroupSpec("Main", null, List.of("Games"), true),
                        new GroupSpec("Games", null, List.of("Main"), false)),
                List.of());

        JPopupMenu menu = Desktop2DStartMenu.build(model, item -> { });
        assertEquals(0, menu.getComponentCount(),
                "an empty category is noise and is not rendered");
    }

    @Test
    @DisplayName("a model with no root group shows the placeholder row")
    void noRootShowsPlaceholder() {
        JPopupMenu menu = Desktop2DStartMenu.build(
                new MenuModel(List.of(), List.of()), item -> { });
        assertEquals(1, menu.getComponentCount());
        assertEquals(Desktop2DStartMenu.NO_APPS_LABEL,
                ((javax.swing.JMenuItem) menu.getComponent(0)).getText());
    }

    @Test
    @DisplayName("chrome icons load at the requested edge while menu icons stay at 16")
    void iconEdges() {
        // On the test classpath the core resources carry no "resources/"
        // prefix (that tree is assembled by :lg3d-core:runtimeResources);
        // icon() itself is prefix-agnostic.
        String logo = "images/icon/lg3d-logo.png";
        javax.swing.Icon chrome = Desktop2DStartMenu.icon(logo, 22);
        assertNotNull(chrome,
                "the Looking Glass logo should resolve on the test classpath");
        assertEquals(22, chrome.getIconWidth());
        assertEquals(22, chrome.getIconHeight());
        javax.swing.Icon menu = Desktop2DStartMenu.icon(logo);
        assertEquals(16, menu.getIconWidth());
        assertEquals(16, menu.getIconHeight());
        assertNull(Desktop2DStartMenu.icon(null, 22));
        assertNull(Desktop2DStartMenu.icon("   ", 22));
        assertNull(Desktop2DStartMenu.icon(logo, 0));
        assertNull(Desktop2DStartMenu.icon("images/icon/no-such-glyph.png", 22));
    }

    @Test
    @DisplayName("prefixed descriptor icons resolve via the packaged unprefixed copy")
    void resourcesPrefixedDescriptorResolvesOnPlainClasspath() {
        // Descriptors name artwork under the legacy "resources/" prefix (e.g.
        // Espresso's branded cup), but that tree is assembled only on the Gradle
        // run classpath (:lg3d-core:runtimeResources). On a jar or IDE module
        // classpath the same PNG is packaged at the root WITHOUT the prefix, so
        // the raw prefixed lookup misses and the icon would silently degrade to a
        // generated initials tile. resolveResource must fall back to the
        // unprefixed copy so branded artwork renders in every launch topology.
        String prefixed = "resources/images/icon/espresso.png";
        assertNotNull(Desktop2DStartMenu.resolveResource(prefixed),
                "the prefixed Espresso cup must resolve via the unprefixed fallback");
        assertEquals(
                Desktop2DStartMenu.class.getClassLoader()
                        .getResource("images/icon/espresso.png"),
                Desktop2DStartMenu.resolveResource(prefixed),
                "the fallback must locate the packaged unprefixed copy");
        assertNotNull(Desktop2DStartMenu.icon(prefixed),
                "the cup must load as a menu icon straight from the descriptor path");
        // A resource shipped under neither layout still resolves to null.
        assertNull(Desktop2DStartMenu.resolveResource("resources/images/icon/no-such.png"));
    }
}
