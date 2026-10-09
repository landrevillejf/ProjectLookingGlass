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
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The editor's status line: caret position, selection size, document size,
 * file metadata (charset, line endings, language), font size and a transient
 * message slot. All setters are synchronous plain {@code setText} calls (the
 * panel always invokes them on the calling thread), so headless tests can
 * read the getters back deterministically.
 */
public final class EditorStatusBar extends JPanel {

    private final JLabel positionLabel = new JLabel("Ln 1, Col 1");
    private final JLabel selectionLabel = new JLabel(" ");
    private final JLabel sizeLabel = new JLabel("0 chars, 1 line");
    private final JLabel fileLabel = new JLabel("UTF-8 \u00B7 LF \u00B7 Plain Text");
    private final JLabel fontLabel = new JLabel("14 pt");
    private final JLabel messageLabel = new JLabel(" ");

    public EditorStatusBar() {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        java.awt.Color.GRAY),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(positionLabel);
        left.add(strut());
        left.add(selectionLabel);
        left.add(strut());
        left.add(sizeLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(messageLabel);
        right.add(fontLabel);
        right.add(fileLabel);

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
    }

    private static java.awt.Component strut() {
        return Box.createHorizontalStrut(14);
    }

    /** Shows "Ln x, Col y" for the caret. */
    public void setPosition(int line, int column) {
        positionLabel.setText("Ln " + line + ", Col " + column);
    }

    public String getPosition() {
        return positionLabel.getText();
    }

    /** Shows the selection size, or blanks it when nothing is selected. */
    public void setSelection(int chars) {
        selectionLabel.setText(chars > 0 ? "(" + chars + " selected)" : " ");
    }

    public String getSelection() {
        return selectionLabel.getText();
    }

    /** Shows the document size in characters and lines. */
    public void setDocumentSize(int chars, int lines) {
        sizeLabel.setText(chars + " chars, " + lines
                + (lines == 1 ? " line" : " lines"));
    }

    public String getDocumentSize() {
        return sizeLabel.getText();
    }

    /** Shows charset, line-ending convention and detected language. */
    public void setFileInfo(String charset, String eol, String language) {
        fileLabel.setText(charset + " \u00B7 "
                + (TextFileIO.EOL_CRLF.equals(eol) ? "CRLF" : "LF")
                + " \u00B7 " + language);
    }

    public String getFileInfo() {
        return fileLabel.getText();
    }

    /** Shows the editor font size in points. */
    public void setFontSize(int points) {
        fontLabel.setText(points + " pt");
    }

    public String getFontSize() {
        return fontLabel.getText();
    }

    /** Shows a transient message (cleared by the panel after a few seconds). */
    public void setMessage(String message) {
        messageLabel.setText((message == null || message.isEmpty())
                ? " " : message);
    }

    public String getMessage() {
        String text = messageLabel.getText();
        return " ".equals(text) ? "" : text;
    }
}
