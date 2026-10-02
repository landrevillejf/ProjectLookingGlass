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
package org.jdesktop.lg3d.apps.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the Application Launcher's drag glue. The payload builder
 * and the transferable are pure (no {@code Toolkit}, no display), so they run
 * under {@code java.awt.headless=true}; only the gesture installation touches
 * AWT and is exercised at runtime, not here. The drag source and the 2D
 * taskbar's drop target share {@link Desktop2D#QUICK_LAUNCH_FLAVOR}, and the
 * {@link Desktop2D.QuickLaunchItem} crosses by reference within the one desktop
 * JVM.
 */
class QuickLaunchDragTest {

    @Test
    @DisplayName("item trims the form fields into a quick-launch payload")
    void itemBuildsFromFormFields() {
        Desktop2D.QuickLaunchItem item = QuickLaunchDrag.item(
                "  Mail  ", "  java mail.Mail  ",
                "resource:///resources/images/icon/mail.png");
        assertNotNull(item);
        assertEquals("Mail", item.name());
        assertEquals("java mail.Mail", item.command());
        assertEquals("resources/images/icon/mail.png", item.iconResource(),
                "the resource:/// scheme is stripped to a classpath path");
    }

    @Test
    @DisplayName("item is null when there is no command to launch")
    void itemRejectsBlankCommand() {
        assertNull(QuickLaunchDrag.item("Mail", null, null));
        assertNull(QuickLaunchDrag.item("Mail", "   ", null),
                "a blank command has nothing to launch, so no drag starts");
    }

    @Test
    @DisplayName("a blank launcher name falls back to the command")
    void itemNameFallsBackToCommand() {
        assertEquals("java mail.Mail",
                QuickLaunchDrag.item("", "java mail.Mail", null).name());
        assertEquals("java mail.Mail",
                QuickLaunchDrag.item(null, "java mail.Mail", null).name());
    }

    @Test
    @DisplayName("normalizeIcon strips the resource scheme, keeps file paths")
    void normalizeIconStripsResourceScheme() {
        assertEquals("resources/images/icon/x.png",
                QuickLaunchDrag.normalizeIcon("resource:///resources/images/icon/x.png"));
        assertEquals("/home/u/icon.png",
                QuickLaunchDrag.normalizeIcon("/home/u/icon.png"),
                "an absolute file path is passed through unchanged");
        assertNull(QuickLaunchDrag.normalizeIcon(null));
        assertNull(QuickLaunchDrag.normalizeIcon("  "));
    }

    @Test
    @DisplayName("the transferable carries the item and the command text")
    void transferableCarriesItemAndCommand() throws Exception {
        Desktop2D.QuickLaunchItem item =
                new Desktop2D.QuickLaunchItem("Mail", "java mail.Mail", "mail.png");
        Transferable transferable = QuickLaunchDrag.transferable(item);

        assertTrue(transferable.isDataFlavorSupported(Desktop2D.QUICK_LAUNCH_FLAVOR));
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertFalse(transferable.isDataFlavorSupported(DataFlavor.imageFlavor));

        assertSame(item, transferable.getTransferData(Desktop2D.QUICK_LAUNCH_FLAVOR),
                "the item crosses the same-JVM drag by reference");
        assertEquals("java mail.Mail", transferable.getTransferData(DataFlavor.stringFlavor));

        assertThrows(UnsupportedFlavorException.class,
                () -> transferable.getTransferData(DataFlavor.imageFlavor));
    }
}
