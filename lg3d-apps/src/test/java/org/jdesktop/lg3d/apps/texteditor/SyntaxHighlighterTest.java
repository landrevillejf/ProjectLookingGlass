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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import org.jdesktop.lg3d.apps.texteditor.SyntaxHighlighter.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure {@link SyntaxHighlighter#tokenize} scanner and
 * the batch {@link SyntaxHighlighter#apply} painter. Tokenisation is pure
 * string logic, so the interesting cases (comments before markup, escapes,
 * unterminated runs, keyword casing, merged plain runs) are asserted exactly.
 */
class SyntaxHighlighterTest {

    private static final Language JAVA = Languages.forName("Java");
    private static final Language XML = Languages.forName("XML");
    private static final Language C = Languages.forName("C / C++");
    private static final Language SQL = Languages.forName("SQL");
    private static final Language PY = Languages.forName("Python");

    private static Token only(List<Token> tokens, TokenKind kind) {
        List<Token> hits = tokens.stream()
                .filter(t -> t.kind() == kind).toList();
        assertEquals(1, hits.size(), "expected exactly one " + kind);
        return hits.get(0);
    }

    private static String slice(String text, Token token) {
        return text.substring(token.start(), token.end());
    }

    @Test
    @DisplayName("empty and null text tokenise to nothing")
    void emptyText() {
        assertTrue(SyntaxHighlighter.tokenize(null, JAVA).isEmpty());
        assertTrue(SyntaxHighlighter.tokenize("", JAVA).isEmpty());
    }

    @Test
    @DisplayName("tokens tile the whole input without gaps or overlaps")
    void fullCoverage() {
        String text = "public class Demo { // hi\n  String s = \"x\\\"y\";\n}\n";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, JAVA);
        int at = 0;
        for (Token token : tokens) {
            assertEquals(at, token.start(), "gap or overlap before " + token);
            at = token.end();
        }
        assertEquals(text.length(), at, "tokens must reach EOF");
    }

    @Test
    @DisplayName("Java keywords, strings, comments and numbers tokenise")
    void javaBasics() {
        String text = "final int n = 42; // count\nString s = \"lit\"; /* b */";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, JAVA);
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == TokenKind.KEYWORD
                && "final".equals(slice(text, t))));
        assertEquals("42", slice(text, only(tokens, TokenKind.NUMBER)));
        assertEquals("\"lit\"", slice(text, only(tokens, TokenKind.STRING)));
        // Two comment runs: the line comment and the block comment.
        List<Token> comments = tokens.stream()
                .filter(t -> t.kind() == TokenKind.COMMENT).toList();
        assertEquals(2, comments.size());
        assertEquals("// count", slice(text, comments.get(0)));
        assertEquals("/* b */", slice(text, comments.get(1)));
    }

    @Test
    @DisplayName("an escaped quote does not end a string; unterminated runs to EOF")
    void stringEscapes() {
        String escaped = "String s = \"a\\\"b\";";
        List<Token> tokens = SyntaxHighlighter.tokenize(escaped, JAVA);
        assertEquals("\"a\\\"b\"",
                slice(escaped, only(tokens, TokenKind.STRING)));

        String open = "x = \"never closed";
        List<Token> openTokens = SyntaxHighlighter.tokenize(open, JAVA);
        Token string = only(openTokens, TokenKind.STRING);
        assertEquals(open.length(), string.end(),
                "an unterminated string colours through EOF");
    }

    @Test
    @DisplayName("an unterminated block comment also runs to EOF")
    void unterminatedBlockComment() {
        String text = "code /* dangling";
        Token comment = only(SyntaxHighlighter.tokenize(text, JAVA),
                TokenKind.COMMENT);
        assertEquals(text.length(), comment.end());
    }

    @Test
    @DisplayName("XML comments win over the markup branch")
    void xmlCommentBeforeMarkup() {
        String text = "<!-- note --><tag attr=\"v\"/>";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, XML);
        Token comment = only(tokens, TokenKind.COMMENT);
        assertEquals("<!-- note -->", slice(text, comment));
        Token tag = only(tokens, TokenKind.TAG);
        assertEquals("<tag attr=\"v\"/>", slice(text, tag));
        // Declarations: <!DOCTYPE ...> and <?xml ...?>.
        String decl = "<?xml version=\"1.0\"?><!DOCTYPE x>";
        List<Token> declTokens = SyntaxHighlighter.tokenize(decl, XML);
        assertEquals(2, declTokens.stream()
                .filter(t -> t.kind() == TokenKind.DECLARATION).count());
    }

    @Test
    @DisplayName("preprocessor directives need C-family languages and a line start")
    void preprocessor() {
        String text = "#include <stdio.h>\nint x; // # not a directive";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, C);
        Token directive = only(tokens, TokenKind.DIRECTIVE);
        assertEquals("#include <stdio.h>", slice(text, directive));
        // Java has no preprocessor: '#' stays plain.
        List<Token> javaTokens =
                SyntaxHighlighter.tokenize("#define x", JAVA);
        assertTrue(javaTokens.stream()
                .noneMatch(t -> t.kind() == TokenKind.DIRECTIVE));
    }

    @Test
    @DisplayName("SQL keywords match case-insensitively via the uppercase retry")
    void sqlKeywordCasing() {
        String text = "select a from t";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, SQL);
        assertEquals(2, tokens.stream()
                .filter(t -> t.kind() == TokenKind.KEYWORD).count());
    }

    @Test
    @DisplayName("Python honours # comments and identifiers are not keywords")
    void pythonAndPlainWords() {
        String text = "def f(x):  # fn\n    return myvar";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, PY);
        List<Token> keywords = tokens.stream()
                .filter(t -> t.kind() == TokenKind.KEYWORD).toList();
        assertEquals(2, keywords.size()); // def, return
        assertEquals("def", slice(text, keywords.get(0)));
        assertEquals("return", slice(text, keywords.get(1)));
        assertEquals("# fn", slice(text, only(tokens, TokenKind.COMMENT)));
        // "myvar" is an identifier, not a keyword: it stays in a PLAIN run.
        assertFalse(tokens.stream().anyMatch(t ->
                t.kind() == TokenKind.KEYWORD
                        && "myvar".equals(slice(text, t))));
    }

    @Test
    @DisplayName("hex and exponent numbers tokenise as one NUMBER each")
    void numbers() {
        String text = "0xFF 1.5e-3 42";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, JAVA);
        List<Token> nums = tokens.stream()
                .filter(t -> t.kind() == TokenKind.NUMBER).toList();
        assertEquals(3, nums.size());
        assertEquals("0xFF", slice(text, nums.get(0)));
        assertEquals("1.5e-3", slice(text, nums.get(1)));
        assertEquals("42", slice(text, nums.get(2)));
    }

    @Test
    @DisplayName("Plain Text yields a single merged PLAIN run")
    void plainLanguage() {
        String text = "just some words 123 <not markup>";
        List<Token> tokens = SyntaxHighlighter.tokenize(text, null);
        assertEquals(1, tokens.size());
        assertEquals(TokenKind.PLAIN, tokens.get(0).kind());
        assertEquals(0, tokens.get(0).start());
        assertEquals(text.length(), tokens.get(0).end());
    }

    @Test
    @DisplayName("the size gate refuses documents above the cap")
    void shouldHighlightGate() {
        assertTrue(SyntaxHighlighter.shouldHighlight(0));
        assertTrue(SyntaxHighlighter.shouldHighlight(
                SyntaxHighlighter.MAX_HIGHLIGHT_CHARS));
        assertFalse(SyntaxHighlighter.shouldHighlight(
                SyntaxHighlighter.MAX_HIGHLIGHT_CHARS + 1));
    }

    @Test
    @DisplayName("apply colours a styled document without throwing")
    void applyPaints() {
        DefaultStyledDocument doc = new DefaultStyledDocument();
        assertDoesNotThrow(() -> doc.insertString(0,
                "public class X {} // c", null));
        SyntaxHighlighter.apply(doc, JAVA, EditorTheme.DARK);
        // The keyword "public" carries the theme's keyword colour.
        java.awt.Color keyword =
                EditorTheme.DARK.colorFor(TokenKind.KEYWORD);
        assertEquals(keyword, StyleConstants.getForeground(
                doc.getCharacterElement(0).getAttributes()));
        // Null arguments are tolerated.
        assertDoesNotThrow(() -> SyntaxHighlighter.apply(null, JAVA,
                EditorTheme.LIGHT));
        assertDoesNotThrow(() -> SyntaxHighlighter.apply(doc, null, null));
    }
}
