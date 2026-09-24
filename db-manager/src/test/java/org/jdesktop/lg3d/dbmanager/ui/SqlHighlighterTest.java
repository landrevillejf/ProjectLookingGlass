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
package org.jdesktop.lg3d.dbmanager.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.awt.Color;
import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SqlHighlighter}: it installs on a {@link JTextPane}, re-scans on
 * edits without throwing, and colours keywords (bold), string literals,
 * numbers and comments with the expected attributes.
 */
class SqlHighlighterTest {

    private static final Color KEYWORD = new Color(0x00, 0x33, 0xB3);
    private static final Color STRING = new Color(0x06, 0x7D, 0x17);
    private static final Color NUMBER = new Color(0x17, 0x50, 0xEB);

    private static JTextPane paneWith(SqlHighlighter[] out, String sql) {
        JTextPane pane = new JTextPane();
        pane.setDocument(SqlHighlighter.newDocument());
        // Set the text first so the highlighter's constructor rescans it
        // synchronously (outside any document notification); later edits are
        // deferred to the EDT, so this ordering keeps style reads deterministic
        // without pumping the event queue.
        pane.setText(sql);
        out[0] = new SqlHighlighter(pane);
        return pane;
    }

    private static AttributeSet attrsAt(JTextPane pane, int offset) {
        StyledDocument doc = (StyledDocument) pane.getDocument();
        return doc.getCharacterElement(offset).getAttributes();
    }

    @Test
    @DisplayName("newDocument yields a styled document")
    void newDocument() {
        DefaultStyledDocument doc = SqlHighlighter.newDocument();
        assertThat(doc).isNotNull();
    }

    @Test
    @DisplayName("a SQL keyword is rendered bold and in the keyword colour")
    void keywordIsBold() {
        SqlHighlighter[] h = new SqlHighlighter[1];
        String sql = "SELECT name FROM people";
        JTextPane pane = paneWith(h, sql);
        AttributeSet atSelect = attrsAt(pane, 0);
        assertThat(StyleConstants.isBold(atSelect)).isTrue();
        assertThat(StyleConstants.getForeground(atSelect)).isEqualTo(KEYWORD);
        // A non-keyword identifier is not bold.
        int nameIdx = sql.indexOf("name");
        assertThat(StyleConstants.isBold(attrsAt(pane, nameIdx))).isFalse();
    }

    @Test
    @DisplayName("a string literal is rendered in the string colour")
    void stringIsColoured() {
        SqlHighlighter[] h = new SqlHighlighter[1];
        String sql = "SELECT * FROM t WHERE name = 'literal'";
        JTextPane pane = paneWith(h, sql);
        int idx = sql.indexOf("'literal'");
        assertThat(StyleConstants.getForeground(attrsAt(pane, idx + 1))).isEqualTo(STRING);
    }

    @Test
    @DisplayName("a numeric literal is rendered in the number colour")
    void numberIsColoured() {
        SqlHighlighter[] h = new SqlHighlighter[1];
        String sql = "SELECT 123";
        JTextPane pane = paneWith(h, sql);
        int idx = sql.indexOf("123");
        assertThat(StyleConstants.getForeground(attrsAt(pane, idx))).isEqualTo(NUMBER);
    }

    @Test
    @DisplayName("line and block comments are rendered italic")
    void commentsAreItalic() {
        SqlHighlighter[] h = new SqlHighlighter[1];
        String sql = "SELECT 1 -- trailing note\n/* block */";
        JTextPane pane = paneWith(h, sql);
        int lineIdx = sql.indexOf("-- trailing");
        assertThat(StyleConstants.isItalic(attrsAt(pane, lineIdx))).isTrue();
        int blockIdx = sql.indexOf("/* block");
        assertThat(StyleConstants.isItalic(attrsAt(pane, blockIdx))).isTrue();
    }

    @Test
    @DisplayName("editing re-highlights without throwing; changedUpdate is a no-op")
    void editsAreSafe() {
        SqlHighlighter[] h = new SqlHighlighter[1];
        JTextPane pane = paneWith(h, "SELECT 1");
        assertThatCode(() -> {
            pane.setText("INSERT INTO t VALUES ('a;b') -- c");
            h[0].highlight();
            h[0].changedUpdate(null);
        }).doesNotThrowAnyException();
        assertThat(pane.getText()).contains("INSERT");
    }

    @Test
    @DisplayName("highlight tolerates an empty buffer")
    void emptyBuffer() {
        SqlHighlighter[] h = new SqlHighlighter[1];
        JTextPane pane = paneWith(h, "");
        assertThatCode(() -> h[0].highlight()).doesNotThrowAnyException();
        assertThat(pane.getText()).isEmpty();
    }
}
