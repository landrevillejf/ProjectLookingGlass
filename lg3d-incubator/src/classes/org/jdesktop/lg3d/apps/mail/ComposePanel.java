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
package org.jdesktop.lg3d.apps.mail;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.ContactDirectory;

/**
 * The compose editor: From (account) / To / Cc / Bcc / Subject fields, a body area,
 * an attachment list with add/remove, and a contact picker fed from the desktop-wide
 * address book ({@link ContactDirectory} over the shared
 * {@code org.jdesktop.lg3d.contacts.ContactStore}).
 *
 * <p>It is a plain {@link JPanel} so it can be dropped into a dialog by
 * {@link MailPanel} for the user and driven directly by the headless tests. It knows
 * how to prefill itself for reply / reply-all / forward (quoting the source) and to
 * {@link #buildDraft()} a {@link MailMessage} plus expose the chosen account and the
 * attachment list, which {@link MailPanel} hands to the backend to send.</p>
 */
final class ComposePanel extends JPanel {

    private final JComboBox<MailAccount> accountBox = new JComboBox<MailAccount>();
    private final JTextField toField = new JTextField();
    private final JTextField ccField = new JTextField();
    private final JTextField bccField = new JTextField();
    private final JTextField subjectField = new JTextField();
    private final JTextArea body = new JTextArea();

    private final DefaultListModel<MailAttachment> attachmentModel =
            new DefaultListModel<MailAttachment>();
    private final JList<MailAttachment> attachmentList =
            new JList<MailAttachment>(attachmentModel);

    private final DefaultListModel<String> contactModel = new DefaultListModel<String>();
    private final JList<String> contactList = new JList<String>(contactModel);
    private final List<MailAddress> contactAddresses = new ArrayList<MailAddress>();

    ComposePanel(List<MailAccount> accounts, MailAccount defaultAccount) {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        if (accounts != null) {
            for (MailAccount a : accounts) {
                accountBox.addItem(a);
            }
        }
        if (defaultAccount != null) {
            accountBox.setSelectedItem(defaultAccount);
        }

        add(buildHeader(), BorderLayout.NORTH);

        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        add(new JScrollPane(body), BorderLayout.CENTER);

        add(buildEast(), BorderLayout.EAST);
        add(buildSouth(), BorderLayout.SOUTH);

        loadContacts();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new GridLayout(5, 1, 4, 4));
        header.add(row("From:", accountBox));
        header.add(row("To:", toField));
        header.add(row("Cc:", ccField));
        header.add(row("Bcc:", bccField));
        header.add(row("Subject:", subjectField));
        return header;
    }

    private static JPanel row(String label, java.awt.Component field) {
        JPanel r = new JPanel(new BorderLayout(4, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new java.awt.Dimension(64, 24));
        r.add(l, BorderLayout.WEST);
        r.add(field, BorderLayout.CENTER);
        return r;
    }

    private JPanel buildEast() {
        JPanel east = new JPanel(new BorderLayout(4, 4));
        east.setPreferredSize(new java.awt.Dimension(190, 100));
        east.setBorder(BorderFactory.createTitledBorder("Contacts"));
        contactList.setVisibleRowCount(12);
        JButton add = new JButton("Add to To");
        add.addActionListener(e -> addSelectedContactToTo());
        east.add(new JScrollPane(contactList), BorderLayout.CENTER);
        east.add(add, BorderLayout.SOUTH);
        return east;
    }

    private JPanel buildSouth() {
        JPanel south = new JPanel(new BorderLayout(4, 4));
        attachmentList.setVisibleRowCount(3);
        JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton attach = new JButton("Attach...");
        attach.addActionListener(e -> chooseAttachment());
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> removeSelectedAttachment());
        buttons.add(attach);
        buttons.add(remove);
        south.setBorder(BorderFactory.createTitledBorder("Attachments"));
        south.add(new JScrollPane(attachmentList), BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);
        return south;
    }

    private void loadContacts() {
        try {
            ContactDirectory directory = new ContactDirectory();
            for (ContactDirectory.ContactInfo c : directory.getContacts()) {
                MailAddress addr = new MailAddress(c.displayName,
                        c.email == null ? "" : c.email);
                contactAddresses.add(addr);
                contactModel.addElement(addr.format());
            }
        } catch (RuntimeException e) {
            // No address book available; the contact picker simply stays empty.
        }
    }

    private void addSelectedContactToTo() {
        int i = contactList.getSelectedIndex();
        if (i < 0 || i >= contactAddresses.size()) {
            return;
        }
        String existing = toField.getText().trim();
        String add = contactAddresses.get(i).format();
        toField.setText(existing.isEmpty() ? add : existing + ", " + add);
    }

    void chooseAttachment() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            addAttachment(chooser.getSelectedFile());
        }
    }

    void addAttachment(File file) {
        if (file != null && file.isFile()) {
            attachmentModel.addElement(MailAttachment.fromFile(file));
        }
    }

    void removeSelectedAttachment() {
        int i = attachmentList.getSelectedIndex();
        if (i >= 0) {
            attachmentModel.remove(i);
        }
    }

    // ------------------------------------------------------------------
    // Prefill
    // ------------------------------------------------------------------

    /** Sets the body/signature for a brand-new message from the chosen account. */
    void initNew() {
        MailAccount a = selectedAccount();
        if (a != null && !a.getSignature().isEmpty()) {
            body.setText("\n\n" + a.getSignature());
            body.setCaretPosition(0);
        }
    }

    /** Prefills a reply (all=true includes Cc recipients), quoting the source. */
    void prefillReply(MailMessage src, boolean all) {
        if (src == null) {
            return;
        }
        toField.setText(src.getFrom().format());
        if (all) {
            List<MailAddress> cc = new ArrayList<MailAddress>(src.getCc());
            for (MailAddress t : src.getTo()) {
                if (!isMe(t)) {
                    cc.add(t);
                }
            }
            ccField.setText(MailMessage.join(cc));
        }
        String subject = src.getSubject();
        subjectField.setText(subject.toLowerCase().startsWith("re:")
                ? subject : "Re: " + subject);
        String quoted = src.getTextBody().replace("\n", "\n> ");
        body.setText("Hi " + src.getFrom().display() + ",\n\n> " + quoted
                + "\n\n" + signatureBlock());
        body.setCaretPosition(0);
    }

    /** Prefills a forward of the source message. */
    void prefillForward(MailMessage src) {
        if (src == null) {
            return;
        }
        String subject = src.getSubject();
        subjectField.setText(subject.toLowerCase().startsWith("fwd:")
                ? subject : "Fwd: " + subject);
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n----- Forwarded message -----\n");
        sb.append("From: ").append(src.getFrom().format()).append('\n');
        sb.append("To: ").append(src.toLine()).append('\n');
        sb.append("Subject: ").append(src.getSubject()).append('\n');
        sb.append("Date: ").append(new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm").format(new java.util.Date(src.getWhen())))
                .append("\n\n").append(src.getTextBody());
        sb.append("\n\n").append(signatureBlock());
        body.setText(sb.toString());
        body.setCaretPosition(0);
        for (MailAttachment a : src.getAttachments()) {
            attachmentModel.addElement(a);
        }
    }

    private boolean isMe(MailAddress a) {
        MailAccount acct = selectedAccount();
        return acct != null && a.getEmail().equalsIgnoreCase(acct.getEmailAddress());
    }

    private String signatureBlock() {
        MailAccount a = selectedAccount();
        return (a == null || a.getSignature().isEmpty()) ? "" : a.getSignature();
    }

    // ------------------------------------------------------------------
    // Draft assembly
    // ------------------------------------------------------------------

    MailAccount selectedAccount() {
        return (MailAccount) accountBox.getSelectedItem();
    }

    /** Builds the outgoing draft from the current field values. */
    MailMessage buildDraft() {
        MailMessage draft = new MailMessage();
        MailAccount a = selectedAccount();
        if (a != null) {
            draft.setAccountId(a.getId());
            draft.setFrom(a.fromAddress());
        }
        draft.setTo(parseList(toField.getText()));
        draft.setCc(parseList(ccField.getText()));
        draft.setBcc(parseList(bccField.getText()));
        draft.setSubject(subjectField.getText());
        draft.setTextBody(body.getText());
        draft.setSentDate(System.currentTimeMillis());
        return draft;
    }

    List<MailAttachment> attachments() {
        List<MailAttachment> out = new ArrayList<MailAttachment>();
        for (int i = 0; i < attachmentModel.size(); i++) {
            out.add(attachmentModel.get(i));
        }
        return out;
    }

    /** Splits a comma/semicolon separated recipient field into addresses. */
    static List<MailAddress> parseList(String text) {
        List<MailAddress> out = new ArrayList<MailAddress>();
        if (text == null || text.trim().isEmpty()) {
            return out;
        }
        for (String token : text.split("[,;]")) {
            MailAddress a = MailAddress.parse(token);
            if (a != null && a.hasEmail()) {
                out.add(a);
            }
        }
        return out;
    }

    // Test/inspection accessors (package-private).
    String getToText() {
        return toField.getText();
    }

    void setToText(String s) {
        toField.setText(s);
    }

    String getSubjectText() {
        return subjectField.getText();
    }

    void setSubjectText(String s) {
        subjectField.setText(s);
    }

    String getBodyText() {
        return body.getText();
    }

    void setBodyText(String s) {
        body.setText(s);
    }
}
