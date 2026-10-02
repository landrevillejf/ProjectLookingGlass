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
package org.jdesktop.lg3d.apps.contacts;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jdesktop.lg3d.contacts.Contact;
import org.jdesktop.lg3d.contacts.ContactStore;

/**
 * The production address book of the desktop.
 *
 * <p>This replaces the legacy read-only 2D contact browser (and its bundled
 * demo {@code contacts.xml}): a full CRUD contact manager over the shared
 * {@link ContactStore} ({@code ~/.lg3d/contacts/contacts.json}). The store
 * starts empty — there is <b>no demo data</b> — and is the same address book
 * the Agenda attendee picker, the Messenger and the Video Conference read, so
 * one entry created here is reachable from all of them.</p>
 *
 * <p>Features: incremental search over name / e-mail / organisation / tags, a
 * favourites-pinned list, an in-panel editor (no modal dialog escaping the
 * SwingNode capture), multi-valued e-mails / phones / tags, vCard 3.0 import
 * and export, and delete with confirmation. All state lives in the shared
 * store; the panel holds only an in-memory filtered view, so it stays fast.</p>
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} so the one start-menu
 * descriptor opens it as an MDI internal frame in the 2D/Swing desktop, while
 * the {@link Contacts} wrapper hosts it on a {@code SwingNode} in 3D.</p>
 */
public class ContactsPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 780;
    public static final int HEIGHT_PX = 520;

    private final ContactStore store;
    private final DefaultListModel<Contact> listModel = new DefaultListModel<>();
    private final JList<Contact> contactList = new JList<>(listModel);
    private final JTextField searchField = new JTextField();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel centre = new JPanel(cardLayout);
    private final JPanel viewCard = new JPanel(new BorderLayout());
    private final JPanel editCard = new JPanel(new BorderLayout());

    // View card
    private final JLabel viewName = new JLabel(" ");
    private final JLabel viewSubtitle = new JLabel(" ");
    private final JTextArea viewDetails = new JTextArea();

    // Edit card fields
    private final JTextField editFirst = new JTextField();
    private final JTextField editLast = new JTextField();
    private final JTextField editNickname = new JTextField();
    private final JTextField editOrg = new JTextField();
    private final JTextField editTitle = new JTextField();
    private final JTextArea editEmails = new JTextArea(3, 20);
    private final JTextArea editPhones = new JTextArea(3, 20);
    private final JTextField editTags = new JTextField();
    private final JTextArea editNotes = new JTextArea(3, 20);
    private final JCheckBox editFavorite = new JCheckBox("Favourite");

    private Contact editing;   // contact being edited, null when creating

    /** A panel over the real (user) address book. */
    public ContactsPanel() {
        this(new ContactStore());
    }

    /**
     * A panel over an explicit store (tests).
     *
     * @param store the address book to edit
     */
    public ContactsPanel(ContactStore store) {
        super(new BorderLayout(6, 6));
        this.store = store;
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildSplit(), BorderLayout.CENTER);
        add(buildStatus(), BorderLayout.SOUTH);

        refreshList();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        searchField.setColumns(18);
        searchField.setToolTipText("Search name, e-mail, organisation, tags");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshList(); }
            public void removeUpdate(DocumentEvent e) { refreshList(); }
            public void changedUpdate(DocumentEvent e) { refreshList(); }
        });
        bar.add(searchField);
        bar.add(button("New", "Create a new contact", e -> startCreate()));
        bar.add(button("Edit", "Edit the selected contact", e -> startEdit()));
        bar.add(button("Delete", "Delete the selected contact", e -> deleteSelected()));
        bar.add(button("Import vCard", "Import contacts from a .vcf file", e -> importVCard()));
        bar.add(button("Export vCard", "Export all contacts to a .vcf file", e -> exportVCard()));
        return bar;
    }

    private static JButton button(String text, String tip, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(l);
        return b;
    }

    private javax.swing.JSplitPane buildSplit() {
        contactList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        contactList.setCellRenderer(new ContactRenderer());
        contactList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showView(contactList.getSelectedValue());
            }
        });
        JScrollPane listScroll = new JScrollPane(contactList);
        listScroll.setPreferredSize(new Dimension(240, HEIGHT_PX));

        centre.add(viewCard, "view");
        centre.add(editCard, "edit");
        viewCard.add(buildViewCard(), BorderLayout.CENTER);
        editCard.add(buildEditCard(), BorderLayout.CENTER);

        javax.swing.JSplitPane split =
                new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT,
                        listScroll, centre);
        split.setResizeWeight(0.35);
        return split;
    }

    private JPanel buildViewCard() {
        JPanel head = new JPanel(new BorderLayout());
        viewName.setFont(viewName.getFont().deriveFont(Font.BOLD, 18f));
        head.add(viewName, BorderLayout.NORTH);
        head.add(viewSubtitle, BorderLayout.CENTER);
        viewDetails.setEditable(false);
        viewDetails.setLineWrap(true);
        viewDetails.setWrapStyleWord(true);
        viewDetails.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        p.add(head, BorderLayout.NORTH);
        p.add(new JScrollPane(viewDetails), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildEditCard() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        row = addField(form, g, row, "First name", editFirst);
        row = addField(form, g, row, "Last name", editLast);
        row = addField(form, g, row, "Nickname", editNickname);
        row = addField(form, g, row, "Organisation", editOrg);
        row = addField(form, g, row, "Title", editTitle);
        row = addArea(form, g, row, "E-mails (one/line)", editEmails);
        row = addArea(form, g, row, "Phones (one/line)", editPhones);
        row = addField(form, g, row, "Tags (comma)", editTags);
        row = addArea(form, g, row, "Notes", editNotes);
        g.gridx = 1; g.gridy = row; g.weightx = 1;
        form.add(editFavorite, g);
        row++;
        g.gridx = 1; g.gridy = row; g.weighty = 1;
        form.add(new JPanel(), g);   // spacer

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(button("Save", "Save the contact", e -> saveEdit()));
        buttons.add(button("Cancel", "Discard changes", e -> cancelEdit()));

        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(form), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private int addField(JPanel form, GridBagConstraints g, int row, String label, JTextField f) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; g.weighty = 0;
        form.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1;
        form.add(f, g);
        return row + 1;
    }

    private int addArea(JPanel form, GridBagConstraints g, int row, String label, JTextArea a) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; g.weighty = 0;
        form.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1;
        form.add(new JScrollPane(a), g);
        return row + 1;
    }

    private JPanel buildStatus() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel(" ");
        p.add(statusLabel);
        return p;
    }

    private JLabel statusLabel;

    // ------------------------------------------------------------------
    // List / view
    // ------------------------------------------------------------------

    /** Re-reads the store and re-applies the current search filter. */
    public final void refreshList() {
        List<Contact> shown = store.search(searchField.getText());
        Contact selected = contactList.getSelectedValue();
        listModel.clear();
        for (Contact c : shown) {
            listModel.addElement(c);
        }
        if (selected != null && listModel.contains(selected)) {
            contactList.setSelectedValue(selected, true);
        } else if (!listModel.isEmpty()) {
            contactList.setSelectedIndex(0);
        } else {
            showView(null);
        }
        statusLabel.setText(listModel.size() + " contact(s)"
                + (store.size() != listModel.size()
                        ? " (of " + store.size() + ")" : ""));
    }

    /** Populates the read-only detail card from {@code c} (empty state if null). */
    void showView(Contact c) {
        cardLayout.show(centre, "view");
        if (c == null) {
            viewName.setText(store.size() == 0 ? "No contacts yet" : " ");
            viewSubtitle.setText(store.size() == 0
                    ? "Click \u201cNew\u201d to create your first contact." : " ");
            viewDetails.setText("");
            return;
        }
        viewName.setText((c.isFavorite() ? "\u2605 " : "") + c.displayName());
        String sub = c.getOrganization().isEmpty() ? "" : c.getOrganization();
        if (!sub.isEmpty() && !c.getTitle().isEmpty()) {
            sub += " \u2014 ";
        }
        sub += c.getTitle();
        viewSubtitle.setText(sub);
        StringBuilder sb = new StringBuilder();
        if (!c.getNickname().isEmpty()) {
            sb.append("Nickname: ").append(c.getNickname()).append('\n');
        }
        for (String e : c.getEmails()) {
            sb.append("E-mail:   ").append(e).append('\n');
        }
        for (String p : c.getPhones()) {
            sb.append("Phone:    ").append(p).append('\n');
        }
        if (!c.getTags().isEmpty()) {
            sb.append("Tags:     ").append(String.join(", ", c.getTags())).append('\n');
        }
        if (!c.getNotes().isEmpty()) {
            sb.append('\n').append(c.getNotes()).append('\n');
        }
        viewDetails.setText(sb.toString());
        viewDetails.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    void startCreate() {
        editing = null;
        clearEditFields();
        cardLayout.show(centre, "edit");
    }

    void startEdit() {
        Contact c = contactList.getSelectedValue();
        if (c == null) {
            return;
        }
        editing = c;
        editFirst.setText(c.getFirstName());
        editLast.setText(c.getLastName());
        editNickname.setText(c.getNickname());
        editOrg.setText(c.getOrganization());
        editTitle.setText(c.getTitle());
        editEmails.setText(String.join("\n", c.getEmails()));
        editPhones.setText(String.join("\n", c.getPhones()));
        editTags.setText(String.join(", ", c.getTags()));
        editNotes.setText(c.getNotes());
        editFavorite.setSelected(c.isFavorite());
        cardLayout.show(centre, "edit");
    }

    private void clearEditFields() {
        editFirst.setText("");
        editLast.setText("");
        editNickname.setText("");
        editOrg.setText("");
        editTitle.setText("");
        editEmails.setText("");
        editPhones.setText("");
        editTags.setText("");
        editNotes.setText("");
        editFavorite.setSelected(false);
    }

    void saveEdit() {
        Contact c = (editing == null) ? new Contact() : editing;
        c.setFirstName(editFirst.getText().trim());
        c.setLastName(editLast.getText().trim());
        c.setNickname(editNickname.getText().trim());
        c.setOrganization(editOrg.getText().trim());
        c.setTitle(editTitle.getText().trim());
        c.setEmails(splitLines(editEmails.getText()));
        c.setPhones(splitLines(editPhones.getText()));
        c.setTags(splitCommas(editTags.getText()));
        c.setNotes(editNotes.getText().trim());
        c.setFavorite(editFavorite.isSelected());
        if (c.displayName().equals("(unnamed)")) {
            JOptionPane.showMessageDialog(this,
                    "A contact needs at least a name, nickname or e-mail.",
                    "Incomplete", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (editing == null) {
            store.add(c);
        } else {
            store.update(c);
        }
        editing = null;
        refreshList();
    }

    void cancelEdit() {
        editing = null;
        cardLayout.show(centre, "view");
        showView(contactList.getSelectedValue());
    }

    void deleteSelected() {
        Contact c = contactList.getSelectedValue();
        if (c == null) {
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete \u201c" + c.displayName() + "\u201d permanently?",
                "Delete contact", JOptionPane.YES_NO_OPTION);
        if (ok == JOptionPane.YES_OPTION) {
            store.delete(c.getId());
            refreshList();
        }
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> splitCommas(String text) {
        List<String> out = new ArrayList<>();
        for (String tok : text.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // vCard import / export
    // ------------------------------------------------------------------

    void importVCard() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Import vCard (.vcf)");
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            String text = Files.readString(chooser.getSelectedFile().toPath(),
                    StandardCharsets.UTF_8);
            int n = 0;
            for (Contact c : VCard.parse(text)) {
                store.add(c);
                n++;
            }
            refreshList();
            statusLabel.setText("Imported " + n + " contact(s)");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not read the file: " + e,
                    "Import failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    void exportVCard() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Export vCard (.vcf)");
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path out = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(out, VCard.format(store.all()), StandardCharsets.UTF_8);
            statusLabel.setText("Exported " + store.size() + " contact(s)");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not write the file: " + e,
                    "Export failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // Test / inspection accessors
    // ------------------------------------------------------------------

    int getContactCount() {
        return listModel.size();
    }

    JTextField searchField() { return searchField; }
    JTextField editFirstField() { return editFirst; }
    JTextField editLastField() { return editLast; }
    JTextArea editEmailsArea() { return editEmails; }
    JCheckBox editFavoriteBox() { return editFavorite; }

    String getDisplayedName() {
        return viewName.getText();
    }

    /** The contacts currently shown (after the search filter). */
    List<Contact> getShownContacts() {
        List<Contact> out = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) {
            out.add(listModel.get(i));
        }
        return out;
    }

    /** Renders a row: favourite star + display name + primary e-mail. */
    private static final class ContactRenderer extends JLabel
            implements javax.swing.ListCellRenderer<Contact> {
        ContactRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        public java.awt.Component getListCellRendererComponent(
                JList<? extends Contact> list, Contact c, int index,
                boolean isSelected, boolean cellHasFocus) {
            if (c == null) {
                setText(" ");
                return this;
            }
            setText("<html>" + (c.isFavorite() ? "\u2605 " : "")
                    + escape(c.displayName())
                    + "<br><font size=2>" + escape(c.primaryEmail()) + "</font></html>");
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }
            return this;
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /** Minimal vCard 3.0 reader/writer for interoperability. */
    static final class VCard {
        private VCard() { }

        static String format(List<Contact> contacts) {
            StringBuilder sb = new StringBuilder();
            for (Contact c : contacts) {
                sb.append("BEGIN:VCARD\nVERSION:3.0\n");
                sb.append("FN:").append(c.displayName()).append('\n');
                sb.append("N:").append(c.getLastName()).append(';')
                        .append(c.getFirstName()).append(";;;\n");
                if (!c.getNickname().isEmpty()) {
                    sb.append("NICKNAME:").append(c.getNickname()).append('\n');
                }
                for (String e : c.getEmails()) {
                    sb.append("EMAIL:").append(e).append('\n');
                }
                for (String p : c.getPhones()) {
                    sb.append("TEL:").append(p).append('\n');
                }
                if (!c.getOrganization().isEmpty()) {
                    sb.append("ORG:").append(c.getOrganization()).append('\n');
                }
                if (!c.getTitle().isEmpty()) {
                    sb.append("TITLE:").append(c.getTitle()).append('\n');
                }
                if (!c.getTags().isEmpty()) {
                    sb.append("CATEGORIES:").append(String.join(",", c.getTags())).append('\n');
                }
                if (!c.getNotes().isEmpty()) {
                    sb.append("NOTE:").append(c.getNotes().replace("\n", "\\n")).append('\n');
                }
                sb.append("END:VCARD\n");
            }
            return sb.toString();
        }

        static List<Contact> parse(String text) {
            List<Contact> out = new ArrayList<>();
            Contact cur = null;
            for (String raw : text.split("\\R")) {
                String line = raw.trim();
                if (line.equalsIgnoreCase("BEGIN:VCARD")) {
                    cur = new Contact();
                } else if (line.equalsIgnoreCase("END:VCARD")) {
                    if (cur != null) {
                        out.add(cur);
                    }
                    cur = null;
                } else if (cur != null) {
                    apply(cur, line);
                }
            }
            return out;
        }

        private static void apply(Contact c, String line) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                return;
            }
            String key = line.substring(0, colon).split(";")[0].toUpperCase();
            String value = line.substring(colon + 1);
            switch (key) {
                case "N": {
                    String[] parts = value.split(";", -1);
                    if (parts.length > 0) {
                        c.setLastName(parts[0]);
                    }
                    if (parts.length > 1) {
                        c.setFirstName(parts[1]);
                    }
                    break;
                }
                case "NICKNAME": c.setNickname(value); break;
                case "EMAIL": c.getEmails().add(value); break;
                case "TEL": c.getPhones().add(value); break;
                case "ORG": c.setOrganization(value); break;
                case "TITLE": c.setTitle(value); break;
                case "CATEGORIES":
                    for (String t : value.split(",")) {
                        if (!t.trim().isEmpty()) {
                            c.getTags().add(t.trim());
                        }
                    }
                    break;
                case "NOTE": c.setNotes(value.replace("\\n", "\n")); break;
                default: break;   // FN and anything else: displayName() derives
            }
        }
    }
}
