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

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the desktop background context menu built by
 * {@link Desktop2DContextMenu}: its entry labels, order and separators, the
 * conditional Terminal entry, the wallpaper submenu contents, the
 * window-arrangement entries being gated on there being windows open, and that
 * firing each entry invokes the matching {@link Desktop2DContextMenu.Actions}
 * callback. Runs headless: the menu is built and its listeners fired directly,
 * never shown, so no display is required.
 */
class Desktop2DContextMenuTest {

    /** The full entry sequence with a terminal installed and windows open. */
    private static final List<String> FULL_MENU = List.of(
            "Open Terminal",
            "Open File Manager",
            "---",
            "Change Wallpaper",
            "Desktop Settings...",
            "---",
            "Cascade Windows",
            "Tile Windows",
            "Minimize All Windows",
            "Restore All Windows",
            "---",
            "Refresh",
            "Exit...");

    /** Records which callbacks fire, in order. */
    private static final class RecordingActions
            implements Desktop2DContextMenu.Actions {

        final List<String> calls = new ArrayList<>();
        final List<URL> wallpaperUrls = new ArrayList<>();
        boolean terminalAvailable = true;
        int windowCount = 1;

        @Override
        public boolean isTerminalAvailable() {
            return terminalAvailable;
        }

        @Override
        public void openTerminal() {
            calls.add("openTerminal");
        }

        @Override
        public void openFileManager() {
            calls.add("openFileManager");
        }

        @Override
        public void changeWallpaper(URL url) {
            calls.add("changeWallpaper");
            wallpaperUrls.add(url);
        }

        @Override
        public void openDesktopSettings() {
            calls.add("openDesktopSettings");
        }

        @Override
        public void cascadeWindows() {
            calls.add("cascadeWindows");
        }

        @Override
        public void tileWindows() {
            calls.add("tileWindows");
        }

        @Override
        public void minimizeAllWindows() {
            calls.add("minimizeAllWindows");
        }

        @Override
        public void restoreAllWindows() {
            calls.add("restoreAllWindows");
        }

        @Override
        public void refresh() {
            calls.add("refresh");
        }

        @Override
        public void exit() {
            calls.add("exit");
        }

        @Override
        public int windowCount() {
            return windowCount;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static URL url(String spec) {
        try {
            return URI.create(spec).toURL();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** A short label for a component: "---" for separators, else its text. */
    private static String describe(Component c) {
        if (c instanceof JPopupMenu.Separator) {
            return "---";
        }
        if (c instanceof JMenuItem item) {
            return item.getText();
        }
        return "?";
    }

    private static List<String> describe(JPopupMenu menu) {
        List<String> out = new ArrayList<>();
        for (Component c : menu.getComponents()) {
            out.add(describe(c));
        }
        return out;
    }

    /** The top-level entry with the given label, or null if absent. */
    private static JMenuItem find(JPopupMenu menu, String label) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem item && label.equals(item.getText())) {
                return item;
            }
        }
        return null;
    }

    /** Fires an entry's listeners the way a click would, without a peer. */
    private static void fire(JMenuItem item) {
        ActionEvent event = new ActionEvent(item, 0, item.getActionCommand());
        for (ActionListener listener : item.getActionListeners()) {
            listener.actionPerformed(event);
        }
    }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the menu lists every entry in order, with separators")
    void fullMenuStructure() {
        RecordingActions actions = new RecordingActions();
        JPopupMenu menu = Desktop2DContextMenu.build(actions, List.of());
        assertEquals(FULL_MENU, describe(menu));
    }

    @Test
    @DisplayName("the Terminal entry is omitted when no terminal is installed")
    void terminalOmittedWhenUnavailable() {
        RecordingActions actions = new RecordingActions();
        actions.terminalAvailable = false;
        JPopupMenu menu = Desktop2DContextMenu.build(actions, List.of());

        assertNull(find(menu, "Open Terminal"));
        List<String> expected = new ArrayList<>(FULL_MENU);
        expected.remove("Open Terminal");
        assertEquals(expected, describe(menu));
    }

    @Test
    @DisplayName("arrangement entries are disabled with no windows open")
    void arrangementDisabledWithoutWindows() {
        RecordingActions actions = new RecordingActions();
        actions.windowCount = 0;
        JPopupMenu menu = Desktop2DContextMenu.build(actions, List.of());

        for (String label : List.of("Cascade Windows", "Tile Windows",
                "Minimize All Windows", "Restore All Windows")) {
            assertFalse(find(menu, label).isEnabled(),
                    label + " must be disabled with no windows");
        }
        // Launchers/personalisation/session stay usable regardless.
        assertTrue(find(menu, "Open File Manager").isEnabled());
        assertTrue(find(menu, "Desktop Settings...").isEnabled());
        assertTrue(find(menu, "Refresh").isEnabled());
        assertTrue(find(menu, "Exit...").isEnabled());
    }

    @Test
    @DisplayName("arrangement entries are enabled with windows open")
    void arrangementEnabledWithWindows() {
        RecordingActions actions = new RecordingActions();
        actions.windowCount = 3;
        JPopupMenu menu = Desktop2DContextMenu.build(actions, List.of());

        for (String label : List.of("Cascade Windows", "Tile Windows",
                "Minimize All Windows", "Restore All Windows")) {
            assertTrue(find(menu, label).isEnabled(),
                    label + " must be enabled with windows open");
        }
    }

    // ------------------------------------------------------------------
    // Wallpaper submenu
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the wallpaper submenu lists each backdrop by name")
    void wallpaperSubmenuContents() {
        RecordingActions actions = new RecordingActions();
        URL first = url("file:///tmp/Alpha.jpg");
        URL second = url("file:///tmp/Beta.png");
        List<Desktop2DContextMenu.Wallpaper> wallpapers = List.of(
                new Desktop2DContextMenu.Wallpaper("Alpha", first),
                new Desktop2DContextMenu.Wallpaper("Beta", second));

        JPopupMenu menu = Desktop2DContextMenu.build(actions, wallpapers);
        JMenu submenu = (JMenu) find(menu, "Change Wallpaper");
        assertNotNull(submenu);
        assertEquals(2, submenu.getMenuComponentCount());

        JMenuItem alpha = (JMenuItem) submenu.getMenuComponent(0);
        JMenuItem beta = (JMenuItem) submenu.getMenuComponent(1);
        assertEquals("Alpha", alpha.getText());
        assertEquals("Beta", beta.getText());

        fire(beta);
        assertEquals(List.of("changeWallpaper"), actions.calls);
        assertEquals(List.of(second), actions.wallpaperUrls);
    }

    @Test
    @DisplayName("an empty wallpaper list yields a single disabled placeholder")
    void wallpaperSubmenuEmptyPlaceholder() {
        RecordingActions actions = new RecordingActions();
        JPopupMenu menu = Desktop2DContextMenu.build(actions, List.of());

        JMenu submenu = (JMenu) find(menu, "Change Wallpaper");
        assertNotNull(submenu);
        assertEquals(1, submenu.getMenuComponentCount());
        JMenuItem placeholder = (JMenuItem) submenu.getMenuComponent(0);
        assertEquals("(no wallpapers)", placeholder.getText());
        assertFalse(placeholder.isEnabled());
    }

    @Test
    @DisplayName("a null wallpaper list is treated as empty")
    void wallpaperSubmenuNullList() {
        RecordingActions actions = new RecordingActions();
        JPopupMenu menu = Desktop2DContextMenu.build(actions, null);

        JMenu submenu = (JMenu) find(menu, "Change Wallpaper");
        assertNotNull(submenu);
        assertEquals(1, submenu.getMenuComponentCount());
        assertFalse(((JMenuItem) submenu.getMenuComponent(0)).isEnabled());
    }

    // ------------------------------------------------------------------
    // Callback wiring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("firing each entry invokes its matching callback")
    void entriesInvokeCallbacks() {
        RecordingActions actions = new RecordingActions();
        JPopupMenu menu = Desktop2DContextMenu.build(actions, List.of());

        fire(find(menu, "Open Terminal"));
        fire(find(menu, "Open File Manager"));
        fire(find(menu, "Desktop Settings..."));
        fire(find(menu, "Cascade Windows"));
        fire(find(menu, "Tile Windows"));
        fire(find(menu, "Minimize All Windows"));
        fire(find(menu, "Restore All Windows"));
        fire(find(menu, "Refresh"));
        fire(find(menu, "Exit..."));

        assertEquals(List.of(
                "openTerminal",
                "openFileManager",
                "openDesktopSettings",
                "cascadeWindows",
                "tileWindows",
                "minimizeAllWindows",
                "restoreAllWindows",
                "refresh",
                "exit"), actions.calls);
    }

    // ------------------------------------------------------------------
    // Pure helpers on Desktop2D used to label the submenu
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isImage accepts jpg/jpeg/png case-insensitively, rejects others")
    void isImageClassification() {
        assertTrue(Desktop2D.isImage("DreamLakeReflections.jpg"));
        assertTrue(Desktop2D.isImage("photo.JPG"));
        assertTrue(Desktop2D.isImage("scan.Jpeg"));
        assertTrue(Desktop2D.isImage("icon.png"));
        assertFalse(Desktop2D.isImage("notes.txt"));
        assertFalse(Desktop2D.isImage("archive.zip"));
        assertFalse(Desktop2D.isImage("noext"));
    }

    @Test
    @DisplayName("displayName strips the extension at the last dot")
    void displayNameStripsExtension() {
        assertEquals("GrandCanyon-0", Desktop2D.displayName("GrandCanyon-0.jpg"));
        assertEquals("Leaves_and_Sky-0",
                Desktop2D.displayName("Leaves_and_Sky-0.png"));
        assertEquals("a.b", Desktop2D.displayName("a.b.jpg"));
        assertEquals("noext", Desktop2D.displayName("noext"));
        // A leading dot is a hidden-file marker, not an extension separator,
        // so the name is returned unchanged rather than stripped to "".
        assertEquals(".hidden", Desktop2D.displayName(".hidden"));
    }
}
