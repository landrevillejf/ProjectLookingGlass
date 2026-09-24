/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.orgchart.ui.contact;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.ContactDirectory;

/**
 * The 2D/Swing counterpart of the native-3D {@link Contact3D} contact-card
 * browser. It reads the very same shared {@code /contacts}
 * {@link java.util.prefs.Preferences} store (through {@link ContactDirectory},
 * which imports the bundled {@code contacts.xml} on first use) that
 * {@code Contact3D} populates and {@code Agenda3D} / {@code Mail3D} read, so the
 * three apps share one address book across both desktops.
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code Contact3D} main class, so the one shared start-menu descriptor launches
 * this panel as an MDI internal frame in the 2D/Swing desktop. The panel is a
 * read-only browser (a contact list plus a detail card) and touches no Java 3D
 * class, so it also runs where the 3D desktop is unavailable.</p>
 *
 * <p>Named {@code ContactCardsPanel} to avoid the existing 3D
 * {@code ui.contact.ContactPanel} and {@code ui.common.ContactPanel}.</p>
 */
public class ContactCardsPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 620;
    public static final int HEIGHT_PX = 420;

    private static final Color FREE = new Color(0x1E, 0x8E, 0x3E);
    private static final Color BUSY = new Color(0xC5, 0x2A, 0x2A);

    private final ContactDirectory directory = new ContactDirectory();
    private final DefaultListModel<ContactDirectory.ContactInfo> listModel =
            new DefaultListModel<ContactDirectory.ContactInfo>();
    private final JList<ContactDirectory.ContactInfo> contactList =
            new JList<ContactDirectory.ContactInfo>(listModel);

    private final JLabel nameField = new JLabel(" ");
    private final JLabel uidField = new JLabel(" ");
    private final JLabel emailField = new JLabel(" ");
    private final JLabel presenceField = new JLabel(" ");

    public ContactCardsPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        for (ContactDirectory.ContactInfo c : directory.getContacts()) {
            listModel.addElement(c);
        }

        add(buildList(), BorderLayout.WEST);
        add(buildDetail(), BorderLayout.CENTER);

        contactList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        contactList.setCellRenderer(new ContactRenderer());
        contactList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showContact(contactList.getSelectedValue());
            }
        });
        if (!listModel.isEmpty()) {
            contactList.setSelectedIndex(0);
        } else {
            showContact(null);
        }
    }

    private JScrollPane buildList() {
        JScrollPane scroll = new JScrollPane(contactList);
        scroll.setPreferredSize(new Dimension(220, HEIGHT_PX));
        return scroll;
    }

    private JPanel buildDetail() {
        JPanel card = new JPanel(new GridLayout(4, 1, 4, 8));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Contact"),
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        nameField.setFont(nameField.getFont().deriveFont(Font.BOLD, 18f));
        card.add(nameField);
        card.add(emailField);
        card.add(uidField);
        card.add(presenceField);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(card, BorderLayout.NORTH);
        return wrapper;
    }

    /** Populates the detail card from {@code c} (blank when null). */
    void showContact(ContactDirectory.ContactInfo c) {
        if (c == null) {
            nameField.setText("(no contacts)");
            uidField.setText(" ");
            emailField.setText(" ");
            presenceField.setText(" ");
            presenceField.setForeground(FREE);
            return;
        }
        nameField.setText(c.displayName);
        uidField.setText("uid: " + c.uid);
        emailField.setText((c.email == null || c.email.isEmpty())
                ? "e-mail: (none)" : "e-mail: " + c.email);
        presenceField.setText(c.busy ? "Presence: Busy" : "Presence: Free");
        presenceField.setForeground(c.busy ? BUSY : FREE);
    }

    // Test/inspection accessors (package-private; the desktop does not use them).
    int getContactCount() {
        return listModel.size();
    }

    String getDisplayedName() {
        return nameField.getText();
    }

    List<ContactDirectory.ContactInfo> getContacts() {
        return directory.getContacts();
    }

    /** Renders a contact row: display name plus a coloured free/busy dot. */
    private static final class ContactRenderer extends JLabel
            implements javax.swing.ListCellRenderer<ContactDirectory.ContactInfo> {
        ContactRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        public java.awt.Component getListCellRendererComponent(
                JList<? extends ContactDirectory.ContactInfo> list,
                ContactDirectory.ContactInfo c, int index,
                boolean isSelected, boolean cellHasFocus) {
            if (c == null) {
                setText(" ");
                return this;
            }
            setText((c.busy ? "\u25cf " : "\u25cb ") + c.displayName);
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(c.busy ? BUSY : list.getForeground());
            }
            return this;
        }
    }
}
