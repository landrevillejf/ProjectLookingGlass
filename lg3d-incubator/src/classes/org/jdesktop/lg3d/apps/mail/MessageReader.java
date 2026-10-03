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
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * The reading pane: a header block (From / To / Cc / Subject / Date) above the
 * message body, plus a row of attachment chips when the message carries any.
 *
 * <p><b>Security.</b> Plain text is the default and is shown in a {@link JTextArea}
 * that cannot execute or fetch anything. HTML is rendered only when
 * {@link MailSettings#isRenderHtml()} is on, and even then the markup is passed
 * through {@link #sanitizeHtml(String)} to strip scripts, frames, embedded objects
 * and event handlers, and the {@link JEditorPane} is never pointed at a URL, so no
 * remote content (tracking pixels, external stylesheets) is ever loaded.</p>
 */
final class MessageReader extends JPanel {

    /** Invoked when the user clicks an attachment chip (to download / save it). */
    interface AttachmentHandler {
        void save(MailAttachment attachment);
    }

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TEXT = "text";
    private static final String CARD_HTML = "html";

    private final JLabel fromLabel = new JLabel(" ");
    private final JLabel toLabel = new JLabel(" ");
    private final JLabel ccLabel = new JLabel(" ");
    private final JLabel subjectLabel = new JLabel(" ");
    private final JLabel dateLabel = new JLabel(" ");

    private final JTextArea textView = new JTextArea();
    private final JEditorPane htmlView = new JEditorPane();
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel bodyPanel = new JPanel(bodyCards);

    private final JPanel attachmentsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

    private AttachmentHandler attachmentHandler;
    private MailMessage message;
    private MailSettings settings = MailSettings.load();

    MessageReader() {
        super(new BorderLayout());
        add(buildHeader(), BorderLayout.NORTH);

        textView.setEditable(false);
        textView.setLineWrap(true);
        textView.setWrapStyleWord(true);
        htmlView.setEditable(false);
        htmlView.setContentType("text/html");
        htmlView.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);

        bodyPanel.add(new JScrollPane(new JLabel("Select a message to read.")),
                CARD_EMPTY);
        bodyPanel.add(new JScrollPane(textView), CARD_TEXT);
        bodyPanel.add(new JScrollPane(htmlView), CARD_HTML);
        add(bodyPanel, BorderLayout.CENTER);

        attachmentsPanel.setBorder(
                BorderFactory.createTitledBorder("Attachments"));
        add(attachmentsPanel, BorderLayout.SOUTH);
        clear();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new GridLayout(5, 1, 0, 2));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        subjectLabel.setFont(subjectLabel.getFont().deriveFont(Font.BOLD, 15f));
        header.add(subjectLabel);
        header.add(fromLabel);
        header.add(toLabel);
        header.add(ccLabel);
        header.add(dateLabel);
        return header;
    }

    void setAttachmentHandler(AttachmentHandler handler) {
        this.attachmentHandler = handler;
    }

    /** Renders a fully-opened message according to the current settings. */
    void setMessage(MailMessage m) {
        this.message = m;
        if (m == null) {
            clear();
            return;
        }
        subjectLabel.setText("Subject: "
                + (m.getSubject().isEmpty() ? "(no subject)" : m.getSubject()));
        fromLabel.setText("From: " + m.getFrom().format());
        toLabel.setText("To: " + m.toLine());
        String cc = m.ccLine();
        ccLabel.setText(cc.isEmpty() ? " " : "Cc: " + cc);
        dateLabel.setText("Date: " + new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm").format(new java.util.Date(m.getWhen())));

        boolean useHtml = settings.isRenderHtml() && m.hasHtmlBody();
        if (useHtml) {
            htmlView.setText(sanitizeHtml(m.getHtmlBody()));
            htmlView.setCaretPosition(0);
            bodyCards.show(bodyPanel, CARD_HTML);
        } else {
            textView.setText(m.getTextBody().isEmpty() && m.hasHtmlBody()
                    ? stripTags(m.getHtmlBody()) : m.getTextBody());
            textView.setCaretPosition(0);
            bodyCards.show(bodyPanel, m.getTextBody().isEmpty()
                    && !m.hasHtmlBody() ? CARD_EMPTY : CARD_TEXT);
        }
        rebuildAttachments();
        applyFonts();
    }

    private void rebuildAttachments() {
        attachmentsPanel.removeAll();
        if (message != null && message.hasAttachments()) {
            for (final MailAttachment a : message.getAttachments()) {
                javax.swing.JButton chip = new javax.swing.JButton(a.describe());
                chip.setToolTipText(a.getMimeType());
                chip.addActionListener(e -> {
                    if (attachmentHandler != null) {
                        attachmentHandler.save(a);
                    }
                });
                attachmentsPanel.add(chip);
            }
            attachmentsPanel.setVisible(true);
        } else {
            attachmentsPanel.setVisible(false);
        }
        attachmentsPanel.revalidate();
        attachmentsPanel.repaint();
    }

    void clear() {
        this.message = null;
        subjectLabel.setText(" ");
        fromLabel.setText(" ");
        toLabel.setText(" ");
        ccLabel.setText(" ");
        dateLabel.setText(" ");
        textView.setText("");
        htmlView.setText("");
        bodyCards.show(bodyPanel, CARD_EMPTY);
        attachmentsPanel.removeAll();
        attachmentsPanel.setVisible(false);
    }

    MailMessage getMessage() {
        return message;
    }

    /** Re-applies fonts/theme after a settings change. */
    void applySettings(MailSettings s) {
        this.settings = s;
        applyFonts();
        if (message != null) {
            setMessage(message);
        }
    }

    private void applyFonts() {
        Font f = new Font(settings.getReaderFontFamily(), Font.PLAIN,
                settings.getReaderFontSize());
        textView.setFont(f);
        htmlView.setFont(f);
    }

    // ------------------------------------------------------------------
    // HTML safety
    // ------------------------------------------------------------------

    /**
     * Strips the active / remote-loading parts of an HTML mail body. This is a
     * defence-in-depth filter, not a full sanitiser: it removes script/style/iframe/
     * object/embed/link/meta tags, on* event attributes, and {@code src}/{@code href}
     * values that point off-box, so the rendered view cannot execute script or beacon
     * out. Plain text remains the default for maximum safety.
     */
    static String sanitizeHtml(String html) {
        if (html == null) {
            return "";
        }
        String s = html;
        s = s.replaceAll("(?is)<\\s*(script|style|iframe|object|embed|link|meta|form)\\b.*?>", "");
        s = s.replaceAll("(?is)</\\s*(script|style|iframe|object|embed|link|meta|form)\\s*>", "");
        s = s.replaceAll("(?is)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = s.replaceAll("(?is)(src|href|background)\\s*=\\s*(\"\\s*(https?:|//|cid:|data:)[^\"]*\"|'\\s*(https?:|//|cid:|data:)[^']*')",
                "$1=\"\"");
        return s;
    }

    /** Crude tag-strip used to show a text fallback for an HTML-only message. */
    static String stripTags(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("(?is)<\\s*(script|style)\\b.*?>.*?<\\s*/\\s*\\1\\s*>", "")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>", "\n")
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .trim();
    }
}
