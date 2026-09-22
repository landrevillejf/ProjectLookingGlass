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
package org.jdesktop.lg3d.wg.switcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JDesktopPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SwitcherOverlay} headlessly: construction, the no-window
 * {@code install()}/{@code uninstall()} path (the host has no root pane yet),
 * and painting an open session onto an offscreen image so every cell branch
 * (thumbnail, icon, placeholder, selected/unselected, elided label) runs. The
 * live key-binding and show/hide-into-a-real-window behaviour is exercised at
 * runtime on a running 2D desktop.
 */
class SwitcherOverlayTest {

    private static final class FakeModel implements SwitcherModel {
        private final List<SwitcherItem> items;

        FakeModel(List<SwitcherItem> items) {
            this.items = items;
        }

        @Override
        public List<SwitcherItem> items() {
            return items;
        }

        @Override
        public void activate(SwitcherItem item) {
            // not exercised here
        }

        @Override
        public String triggerKeySpec() {
            return "control alt TAB";
        }
    }

    private static Icon solidIcon() {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }

            @Override
            public void paintIcon(java.awt.Component c, java.awt.Graphics g,
                                  int x, int y) {
                g.fillRect(x, y, 16, 16);
            }
        };
    }

    private static List<SwitcherItem> variedItems() {
        Image thumb = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        List<SwitcherItem> list = new ArrayList<>();
        // thumbnail + a long label that must be elided
        list.add(new SwitcherItem(new Object(),
                "A very long window name that should be elided", null, thumb));
        // icon + short label
        list.add(new SwitcherItem(new Object(), "Short", solidIcon()));
        // neither icon nor thumbnail -> placeholder branch, empty label
        list.add(new SwitcherItem(new Object(), "", null));
        return list;
    }

    @Test
    @DisplayName("install/uninstall are safe before the host is in a window")
    void installUninstallWithoutRootPane() {
        JDesktopPane host = new JDesktopPane();
        SwitcherOverlay overlay =
                new SwitcherOverlay(host, new FakeModel(variedItems()));
        assertNotNull(overlay.getController());
        // The host has no root pane yet, so install binds nothing and must not
        // throw; uninstall must be equally safe.
        assertDoesNotThrow(overlay::install);
        assertDoesNotThrow(overlay::uninstall);
    }

    @Test
    @DisplayName("an open session paints every cell branch offscreen")
    void paintsOpenSession() {
        JDesktopPane host = new JDesktopPane();
        SwitcherOverlay overlay =
                new SwitcherOverlay(host, new FakeModel(variedItems()));
        // Open the session and step so the highlight lands on different cells.
        overlay.getController().advance();
        overlay.getController().advance();

        overlay.setBounds(0, 0, 400, 160);
        BufferedImage image =
                new BufferedImage(400, 160, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            assertDoesNotThrow(() -> overlay.paint(g));
        } finally {
            g.dispose();
        }
    }

    @Test
    @DisplayName("painting an inactive overlay is a harmless no-op")
    void paintsNothingWhenInactive() {
        JDesktopPane host = new JDesktopPane();
        SwitcherOverlay overlay =
                new SwitcherOverlay(host, new FakeModel(variedItems()));
        overlay.setBounds(0, 0, 400, 160);
        BufferedImage image =
                new BufferedImage(400, 160, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            assertDoesNotThrow(() -> overlay.paint(g));
        } finally {
            g.dispose();
        }
    }
}
