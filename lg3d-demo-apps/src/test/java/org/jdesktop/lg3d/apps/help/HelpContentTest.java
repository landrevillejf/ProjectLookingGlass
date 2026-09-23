/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.help;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.help.HelpSet;
import javax.help.JHelp;
import javax.help.Map;
import javax.help.NavigatorView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless integrity test for the Help Center's JavaHelp bundle: the HelpSet is
 * discovered on the classpath, parses, carries the expected title and the three
 * standard navigators (Contents / Index / Search), every map target resolves to a
 * real topic URL, and the {@link JHelp} viewer constructs without throwing.
 *
 * <p>Constructing {@code JHelp} (a Swing component, never shown) also serves as
 * the JDK 21 compatibility check for JavaHelp 2.0.05: it exercises the HelpSet,
 * map, TOC, index and search-view parsing paths under the toolchain the desktop
 * actually runs on. Runs with {@code java.awt.headless=true} (set by the module's
 * test task), so it is CI-safe with no X display.</p>
 */
class HelpContentTest {

    /** Every target declared in map.jhm and referenced by toc.xml / index.xml. */
    private static final String[] TARGETS = {
        "overview", "getting-started", "desktop-tour", "windows", "start-menu",
        "taskbar", "widgets", "gestures", "desktop-2d", "apps", "customizing",
        "package-management", "troubleshooting", "about",
    };

    /** The bundled content files that must ship inside the jar. */
    private static final String[] RESOURCES = {
        "lg3d-help.hs", "map.jhm", "toc.xml", "index.xml", "lg3d-help.css",
        "overview.html", "getting-started.html", "desktop-tour.html",
        "windows.html", "start-menu.html", "taskbar.html", "widgets.html",
        "gestures.html", "desktop-2d.html", "apps.html", "customizing.html",
        "package-management.html", "troubleshooting.html", "about.html",
    };

    private static final String CONTENT_PREFIX =
            "org/jdesktop/lg3d/apps/help/helpcontent/";

    private static HelpSet loadHelpSet() throws Exception {
        ClassLoader loader = HelpContentTest.class.getClassLoader();
        URL url = HelpSet.findHelpSet(loader, HelpCenterPanel.HELPSET_PATH);
        assertNotNull(url, "the HelpSet must be discoverable on the classpath");
        return new HelpSet(loader, url);
    }

    @Test
    @DisplayName("every help content resource ships on the classpath")
    void contentResourcesAreBundled() {
        ClassLoader loader = HelpContentTest.class.getClassLoader();
        for (String name : RESOURCES) {
            assertNotNull(loader.getResource(CONTENT_PREFIX + name),
                    "missing bundled help resource: " + name);
        }
    }

    @Test
    @DisplayName("the HelpSet parses and carries the expected title")
    void helpSetParses() throws Exception {
        HelpSet helpSet = loadHelpSet();
        assertEquals("Project Looking Glass Help", helpSet.getTitle());
    }

    @Test
    @DisplayName("the HelpSet declares the Contents, Index and Search views")
    void helpSetHasTheThreeNavigators() throws Exception {
        HelpSet helpSet = loadHelpSet();
        NavigatorView[] views = helpSet.getNavigatorViews();
        assertNotNull(views);
        Set<String> names = new HashSet<>();
        for (NavigatorView view : views) {
            names.add(view.getName());
        }
        assertEquals(new HashSet<>(Arrays.asList("TOC", "Index", "Search")), names,
                "expected exactly the TOC, Index and Search navigators");
    }

    @Test
    @DisplayName("every map target is valid and resolves to a topic URL")
    void mapTargetsResolve() throws Exception {
        HelpSet helpSet = loadHelpSet();
        Map map = helpSet.getCombinedMap();
        assertNotNull(map);
        for (String target : TARGETS) {
            assertTrue(map.isValidID(target, helpSet),
                    "target not present in the combined map: " + target);
            Map.ID id = Map.ID.create(target, helpSet);
            assertNotNull(id, "Map.ID.create returned null for " + target);
            assertNotNull(id.getURL(),
                    "target does not resolve to a topic URL: " + target);
        }
    }

    @Test
    @DisplayName("the home target is the overview topic")
    void homeTargetIsOverview() {
        assertEquals("overview", HelpCenterPanel.HOME_ID);
    }

    @Test
    @DisplayName("the JHelp viewer constructs headless (JDK 21 compatibility)")
    void jhelpViewerConstructs() {
        // Building JHelp eagerly parses the HelpSet and creates each navigator
        // view (including the Search view), so a corrupt bundle or a JavaHelp
        // incompatibility with the running JDK surfaces here rather than only
        // when a user opens the Help Center on the desktop.
        assertDoesNotThrow(() -> new JHelp(loadHelpSet()));
    }
}
