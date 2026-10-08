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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.GroupSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link StartMenuSearch}'s content rebuild: a blank query shows the
 * category tree, a non-blank query shows the ranked matches (or a disabled
 * "no match" row), clearing restores the tree, and Enter launches the top match.
 * The popup and its text field are constructed but never shown, so the tests run
 * headless.
 */
class StartMenuSearchTest {

    /** Index of the first content component (header panel + separator). */
    private static final int CONTENT_START = 2;

    private final List<ItemSpec> launched = new ArrayList<>();

    private StartMenuSearch newSearch() {
        MenuModel model = new MenuModel(
                List.of(new GroupSpec("Main", null, List.of(), true)),
                List.of(
                        new ItemSpec("Calculator",
                                "java org.jdesktop.lg3d.apps.calculator.Calculator",
                                "Do sums", "Main", null),
                        new ItemSpec("Terminal",
                                "java org.jdesktop.lg3d.apps.taskmanager.TaskManager",
                                "Command line", "Main", null),
                        new ItemSpec("File Manager",
                                "java org.jdesktop.lg3d.apps.filemanager.FileManager",
                                "Browse", "Main", null)));
        return new StartMenuSearch(model, launched::add);
    }

    private static JMenuItem contentAt(StartMenuSearch s, int i) {
        return (JMenuItem) s.menu().getComponent(CONTENT_START + i);
    }

    @Test
    @DisplayName("a blank query shows the full category tree")
    void blankShowsTree() {
        StartMenuSearch s = newSearch();
        assertNotNull(s.menu());
        assertEquals(0, s.resultCount(), "no search active");
        assertEquals(3, s.contentCount(), "the three Main items render as the tree");
    }

    @Test
    @DisplayName("a query filters to the ranked matches")
    void queryFilters() {
        StartMenuSearch s = newSearch();
        s.applyQuery("cal");
        assertEquals(1, s.resultCount());
        assertEquals(1, s.contentCount());
        assertEquals("Calculator", contentAt(s, 0).getText());
    }

    @Test
    @DisplayName("clearing the query restores the tree")
    void clearingRestoresTree() {
        StartMenuSearch s = newSearch();
        s.applyQuery("term");
        assertEquals(1, s.resultCount());
        s.applyQuery("");
        assertEquals(0, s.resultCount());
        assertEquals(3, s.contentCount(), "back to the full tree");
    }

    @Test
    @DisplayName("a query with no match shows a disabled placeholder row")
    void noMatchPlaceholder() {
        StartMenuSearch s = newSearch();
        s.applyQuery("zzzz");
        assertEquals(0, s.resultCount());
        assertEquals(1, s.contentCount(), "a single '(no match)' row");
        JMenuItem none = contentAt(s, 0);
        assertEquals(StartMenuSearch.NO_MATCH_LABEL, none.getText());
        assertFalse(none.isEnabled(), "the placeholder is not launchable");
    }

    @Test
    @DisplayName("Enter launches the top match and only the top match")
    void enterLaunchesTopMatch() {
        StartMenuSearch s = newSearch();
        s.applyQuery("file");
        assertTrue(s.resultCount() >= 1);
        s.launchTopMatch();
        assertEquals(1, launched.size());
        assertEquals("File Manager", launched.get(0).getName());
    }

    @Test
    @DisplayName("Enter on the tree (no active search) launches nothing")
    void enterOnTreeLaunchesNothing() {
        StartMenuSearch s = newSearch();
        s.applyQuery("");
        s.launchTopMatch();
        assertTrue(launched.isEmpty(), "no match list to launch from");
    }

    @Test
    @DisplayName("a 3D-only application is hidden from the tree and from search")
    void threeDOnlyIsHidden() {
        MenuModel model = new MenuModel(
                List.of(new GroupSpec("Main", null, List.of(), true)),
                List.of(
                        new ItemSpec("Calculator",
                                "java org.jdesktop.lg3d.apps.calculator.Calculator",
                                "Do sums", "Main", null),
                        // A pure Java 3D command: its main class is neither a
                        // registered panel nor a Swing frame, so classify()
                        // returns UNAVAILABLE and the 2D menu must hide it.
                        new ItemSpec("Space Game",
                                "java org.jdesktop.lg3d.demo.SpaceGame3D",
                                "A pure-3D game", "Main", null)));
        StartMenuSearch s = new StartMenuSearch(model, launched::add);
        assertEquals(1, s.contentCount(),
                "only the runnable Calculator renders; the 3D-only app is hidden");
        s.applyQuery("space");
        assertEquals(0, s.resultCount(),
                "a 3D-only app never surfaces as a search match");
        assertEquals(StartMenuSearch.NO_MATCH_LABEL, contentAt(s, 0).getText(),
                "the query falls through to the '(no match)' placeholder");
    }
}
