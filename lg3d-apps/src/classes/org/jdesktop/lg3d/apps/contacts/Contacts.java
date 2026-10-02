package org.jdesktop.lg3d.apps.contacts;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * 3D-desktop entry point for the Contacts address book.
 *
 * <p>Hosts the Swing {@link ContactsPanel} on a {@code SwingNode} inside a
 * {@code Frame3D} via {@link TitledSwingWindow}, exactly like every other
 * hosted lg3d application. The same panel is reused by the 2D/Swing desktop
 * through {@code Desktop2DAppRegistry.PANEL_APPS}, so both desktops share the
 * one production address book over {@code ~/.lg3d/contacts}.</p>
 */
public class Contacts {

    /** Preferred width of the hosted panel, in pixels. */
    public static final int WIDTH_PX = ContactsPanel.WIDTH_PX;
    /** Preferred height of the hosted panel, in pixels. */
    public static final int HEIGHT_PX = ContactsPanel.HEIGHT_PX;

    public static void main(String[] args) {
        new Contacts();
    }

    public Contacts() {
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Contacts", new ContactsPanel(), WIDTH_PX, HEIGHT_PX);
    }
}
