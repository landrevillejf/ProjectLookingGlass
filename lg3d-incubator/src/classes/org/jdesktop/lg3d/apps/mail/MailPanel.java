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
package org.jdesktop.lg3d.apps.mail;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.ContactDirectory;

/**
 * The 2D/Swing counterpart of the native-3D {@link Mail3D} e-mail client: the
 * same local, {@link java.util.prefs.Preferences}-backed mailbox
 * ({@link MailStore} under {@code /mail/messages}) and the same shared
 * {@code /contacts} recipient directory ({@link ContactDirectory}), rendered as
 * an idiomatic Swing panel instead of a live-texture {@code Component3D}.
 *
 * <p>It is registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code Mail3D} main class, so the one shared start-menu descriptor launches
 * this panel as an MDI internal frame in the 2D/Swing desktop while the 3D
 * desktop keeps building {@code Mail3D}. Because both read and write the same
 * Preferences nodes, a message filed in one desktop is visible in the other.</p>
 *
 * <p>Unlike the click/button-driven 3D view (dev mode routes no keyboard focus
 * into a {@code Frame3D}), the Swing panel uses real widgets: a message list, a
 * reading pane, and an editable compose form with a recipient combo box seeded
 * from the shared contacts. The panel is deliberately free of any Java 3D class
 * so it also runs on a machine without the 3D desktop.</p>
 */
public class MailPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 720;
    public static final int HEIGHT_PX = 480;

    private static final String CARD_READ = "read";
    private static final String CARD_COMPOSE = "compose";

    /** Local identity outgoing messages are sent from (mirrors Mail3D). */
    private static final String ME = "You";
    private static final String ME_EMAIL = "you@example.com";

    /** Recipient fallback when the shared contact directory is empty. */
    private static final String FALLBACK_TO = "Friend";
    private static final String FALLBACK_EMAIL = "friend@example.com";

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final MailStore store = new MailStore();
    private final ContactDirectory directory = new ContactDirectory();
    private final List<MailMessage> all = new ArrayList<MailMessage>();

    private final DefaultListModel<MailMessage> listModel =
            new DefaultListModel<MailMessage>();
    private final JList<MailMessage> messageList = new JList<MailMessage>(listModel);
    private final JComboBox<String> folderBox = new JComboBox<String>(
            new String[] { MailMessage.FOLDER_INBOX, MailMessage.FOLDER_SENT });

    // Reading pane.
    private final JLabel readFrom = new JLabel(" ");
    private final JLabel readTo = new JLabel(" ");
    private final JLabel readSubject = new JLabel(" ");
    private final JLabel readDate = new JLabel(" ");
    private final JTextArea readBody = new JTextArea();

    // Compose pane.
    private final JComboBox<String> toBox = new JComboBox<String>();
    private final JTextField subjectField = new JTextField();
    private final JTextArea composeBody = new JTextArea();
    private final JButton sendButton = new JButton("Send");
    private final JButton discardButton = new JButton("Discard");

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    private final JButton replyButton = new JButton("Reply");
    private final JButton deleteButton = new JButton("Delete");
    private final JButton readButton = new JButton("Mark unread");

    private String folder = MailMessage.FOLDER_INBOX;
    private MailMessage selected;
    private MailMessage draft;          // non-null while composing

    public MailPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        store.seedIfEmpty();
        all.addAll(store.load());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        toBox.setEditable(true);
        reloadContacts();
        showFolder(folder);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        folderBox.addActionListener(e ->
                showFolder((String) folderBox.getSelectedItem()));
        bar.add(folderBox);
        bar.addSeparator();
        JButton newButton = new JButton("New");
        newButton.addActionListener(e -> newMessage());
        bar.add(newButton);
        replyButton.addActionListener(e -> reply());
        bar.add(replyButton);
        deleteButton.addActionListener(e -> deleteSelected());
        bar.add(deleteButton);
        readButton.addActionListener(e -> toggleRead());
        bar.add(readButton);
        return bar;
    }

    private JSplitPane buildBody() {
        messageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        messageList.setCellRenderer(new MessageRenderer());
        messageList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && draft == null) {
                openMessage(messageList.getSelectedValue());
            }
        });
        JScrollPane listScroll = new JScrollPane(messageList);
        listScroll.setPreferredSize(new Dimension(240, HEIGHT_PX));

        cardPanel.add(buildReadCard(), CARD_READ);
        cardPanel.add(buildComposeCard(), CARD_COMPOSE);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                listScroll, cardPanel);
        split.setDividerLocation(240);
        split.setResizeWeight(0.35);
        return split;
    }

    private JPanel buildReadCard() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel header = new JPanel(new java.awt.GridLayout(4, 1));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        readSubject.setFont(readSubject.getFont().deriveFont(Font.BOLD, 15f));
        header.add(readSubject);
        header.add(readFrom);
        header.add(readTo);
        header.add(readDate);
        panel.add(header, BorderLayout.NORTH);

        readBody.setEditable(false);
        readBody.setLineWrap(true);
        readBody.setWrapStyleWord(true);
        readBody.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        panel.add(new JScrollPane(readBody), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildComposeCard() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new java.awt.GridLayout(2, 1));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        top.add(labeled("To:", toBox));
        top.add(labeled("Subject:", subjectField));
        panel.add(top, BorderLayout.NORTH);

        composeBody.setLineWrap(true);
        composeBody.setWrapStyleWord(true);
        panel.add(new JScrollPane(composeBody), BorderLayout.CENTER);

        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        sendButton.addActionListener(e -> send());
        discardButton.addActionListener(e -> discard());
        south.add(discardButton);
        south.add(sendButton);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel labeled(String text, java.awt.Component field) {
        JPanel row = new JPanel(new BorderLayout());
        row.add(new JLabel(text), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Fills the recipient combo box from the shared contact directory. */
    private void reloadContacts() {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
        for (ContactDirectory.ContactInfo c : directory.getContacts()) {
            String entry = (c.email != null && !c.email.isEmpty())
                    ? c.displayName + " <" + c.email + ">"
                    : c.displayName;
            model.addElement(entry);
        }
        toBox.setModel(model);
    }

    /**
     * Switches to {@code target} folder and refreshes the list. Ignored while
     * composing or when already on that folder (mirrors {@code Mail3D.openFolder}).
     */
    void showFolder(String target) {
        if (target == null || draft != null || folder.equals(target)) {
            return;
        }
        folder = target;
        selected = null;
        refreshList();
        clearReading();
        updateActionButtons();
    }

    /** The current folder's messages, newest first (mirrors Mail3D). */
    List<MailMessage> currentFolderMessages() {
        List<MailMessage> out = new ArrayList<MailMessage>();
        for (MailMessage m : all) {
            if (folder.equals(m.getFolder())) {
                out.add(m);
            }
        }
        Collections.sort(out, new Comparator<MailMessage>() {
            public int compare(MailMessage a, MailMessage b) {
                return Long.compare(b.getWhen(), a.getWhen());
            }
        });
        return out;
    }

    private void refreshList() {
        listModel.clear();
        for (MailMessage m : currentFolderMessages()) {
            listModel.addElement(m);
        }
    }

    private void clearReading() {
        readSubject.setText(" ");
        readFrom.setText(" ");
        readTo.setText(" ");
        readDate.setText(" ");
        readBody.setText("");
    }

    /** Opens a message into the reading pane, marking it read. */
    void openMessage(MailMessage m) {
        if (draft != null || m == null) {
            return;
        }
        selected = m;
        if (!m.isRead()) {
            m.setRead(true);
            store.save(m);
        }
        readSubject.setText(m.getSubject());
        readFrom.setText("From: " + m.getFrom()
                + (m.getFromEmail().isEmpty() ? "" : " <" + m.getFromEmail() + ">"));
        readTo.setText("To: " + m.getTo()
                + (m.getToEmail().isEmpty() ? "" : " <" + m.getToEmail() + ">"));
        readDate.setText(DATE_FORMAT.format(new Date(m.getWhen())));
        readBody.setText(m.getBody());
        readBody.setCaretPosition(0);
        cards.show(cardPanel, CARD_READ);
        updateActionButtons();
        refreshList();
    }

    private void updateActionButtons() {
        boolean reading = draft == null && selected != null;
        replyButton.setEnabled(reading);
        deleteButton.setEnabled(reading);
        readButton.setEnabled(reading);
        readButton.setText(selected != null && selected.isRead()
                ? "Mark unread" : "Mark read");
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Starts a blank draft addressed to the first shared contact. */
    void newMessage() {
        if (draft != null) {
            return;
        }
        String toName = FALLBACK_TO;
        String toEmail = FALLBACK_EMAIL;
        ContactDirectory.ContactInfo c = firstContact();
        if (c != null) {
            toName = c.displayName;
            toEmail = (c.email != null) ? c.email : "";
        }
        draft = new MailMessage(store.newId(), ME, ME_EMAIL, toName, toEmail,
                "", "", MailMessage.FOLDER_SENT);
        enterCompose(toEntry(toName, toEmail), "", "");
    }

    /** Starts a draft replying to the selected message. */
    void reply() {
        if (draft != null || selected == null) {
            return;
        }
        MailMessage src = selected;
        String subject = src.getSubject();
        if (!subject.startsWith("Re: ")) {
            subject = "Re: " + subject;
        }
        String quoted = src.getBody().replace("\n", "\n> ");
        String body = "Hi " + src.getFrom() + ",\n\nThanks for the note.\n\n> "
                + quoted;
        draft = new MailMessage(store.newId(), ME, ME_EMAIL,
                src.getFrom(), src.getFromEmail(), subject, body,
                MailMessage.FOLDER_SENT);
        enterCompose(toEntry(src.getFrom(), src.getFromEmail()), subject, body);
    }

    private void enterCompose(String to, String subject, String body) {
        reloadContacts();
        toBox.setSelectedItem(to);
        subjectField.setText(subject);
        composeBody.setText(body);
        composeBody.setCaretPosition(0);
        folderBox.setEnabled(false);
        cards.show(cardPanel, CARD_COMPOSE);
        updateActionButtons();
    }

    /** Files the draft into the Sent folder and jumps the view there. */
    void send() {
        if (draft == null) {
            return;
        }
        applyComposeFields();
        store.save(draft);
        all.add(draft);
        selected = draft;
        draft = null;
        folder = MailMessage.FOLDER_SENT;
        folderBox.setEnabled(true);
        folderBox.setSelectedItem(folder);
        refreshList();
        messageList.setSelectedValue(selected, true);
        openMessage(selected);
    }

    /** Discards the draft without saving and returns to the reading pane. */
    void discard() {
        cancelCompose();
        folderBox.setEnabled(true);
        refreshList();
        cards.show(cardPanel, CARD_READ);
        updateActionButtons();
    }

    private void cancelCompose() {
        draft = null;
    }

    /** Copies the compose widgets back into the draft model. */
    private void applyComposeFields() {
        if (draft == null) {
            return;
        }
        String to = (String) toBox.getEditor().getItem();
        String name = to;
        String email = "";
        if (to != null) {
            int lt = to.indexOf('<');
            int gt = to.indexOf('>');
            if (lt >= 0 && gt > lt) {
                name = to.substring(0, lt).trim();
                email = to.substring(lt + 1, gt).trim();
            }
        }
        draft.setTo(name == null || name.isEmpty() ? FALLBACK_TO : name, email);
        draft.setSubject(subjectField.getText());
        draft.setBody(composeBody.getText());
    }

    void toggleRead() {
        if (draft != null || selected == null) {
            return;
        }
        selected.setRead(!selected.isRead());
        store.save(selected);
        refreshList();
        updateActionButtons();
    }

    void deleteSelected() {
        if (draft != null || selected == null) {
            return;
        }
        all.remove(selected);
        store.delete(selected.getId());
        selected = null;
        refreshList();
        clearReading();
        updateActionButtons();
    }

    private ContactDirectory.ContactInfo firstContact() {
        List<ContactDirectory.ContactInfo> contacts = directory.getContacts();
        return contacts.isEmpty() ? null : contacts.get(0);
    }

    private static String toEntry(String name, String email) {
        return (email == null || email.isEmpty()) ? name : name + " <" + email + ">";
    }

    // Test/inspection accessors (package-private; the desktop does not use them).
    MailMessage getSelected() {
        return selected;
    }

    MailMessage getDraft() {
        return draft;
    }

    String getFolder() {
        return folder;
    }

    int getMessageCount() {
        return all.size();
    }

    /** Renders a message row: an unread dot, the subject and the sender. */
    private static final class MessageRenderer extends JLabel
            implements javax.swing.ListCellRenderer<MailMessage> {
        MessageRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        }

        public java.awt.Component getListCellRendererComponent(
                JList<? extends MailMessage> list, MailMessage m, int index,
                boolean isSelected, boolean cellHasFocus) {
            if (m == null) {
                setText(" ");
                return this;
            }
            String dot = m.isRead() ? "   " : "\u25cf ";
            setText(dot + m.getSubject() + "  \u2014  " + m.getFrom());
            setFont(getFont().deriveFont(m.isRead() ? Font.PLAIN : Font.BOLD));
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(m.isRead() ? list.getForeground() : new Color(0x1A, 0x3D, 0x7C));
            }
            return this;
        }
    }
}
