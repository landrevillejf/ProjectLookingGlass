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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * The Advanced Text Editor's syntax colouriser: a small, dependency-free
 * scanner that turns text into {@link Token}s according to a declarative
 * {@link Language}, plus a batch applier that paints those tokens onto a
 * {@link StyledDocument} with an {@link EditorTheme}'s colours.
 *
 * <p>The scanner is a single forward pass &mdash; comments, strings, numbers,
 * directives, markup tags and keywords &mdash; with adjacent plain runs merged
 * so the token list stays proportional to the interesting content, not to the
 * whitespace. It never throws on malformed input: an unterminated comment or
 * string simply extends to the end of the text, which is what a live editor
 * wants while the user is still typing.</p>
 *
 * <p>{@link #tokenize} is pure (safe off the EDT, unit-testable headless);
 * {@link #apply} is the only Swing-facing half and mutates the document in
 * one batch under no locks of its own, so callers invoke it on the EDT.</p>
 */
public final class SyntaxHighlighter {

    /**
     * Documents longer than this are not colourised: a full re-scan on every
     * keystroke burst would outweigh the benefit for multi-megabyte logs.
     */
    public static final int MAX_HIGHLIGHT_CHARS = 400_000;

    /**
     * One lexed token: a {@code [start, start + length)} span classified as
     * {@code kind}. Spans never overlap and always cover the whole text.
     */
    public record Token(int start, int length, TokenKind kind) {

        /** The exclusive end offset of this token. */
        public int end() {
            return start + length;
        }
    }

    private SyntaxHighlighter() {
        // Static utility.
    }

    /** True when a document of {@code length} chars should be colourised. */
    public static boolean shouldHighlight(int length) {
        return length <= MAX_HIGHLIGHT_CHARS;
    }

    /**
     * Scans {@code text} into a merged, non-overlapping token list covering
     * the whole input. A null language behaves like {@link Languages#PLAIN}.
     */
    public static List<Token> tokenize(String text, Language lang) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        Language language = (lang != null) ? lang : Languages.PLAIN;
        if (language == Languages.PLAIN) {
            // Plain Text is one merged run over the whole input.
            return List.of(new Token(0, text.length(), TokenKind.PLAIN));
        }
        List<Token> out = new ArrayList<>();
        int plainStart = -1;
        int i = 0;
        int n = text.length();
        while (i < n) {
            TokenKind kind = null;
            int end = i;
            char c = text.charAt(i);
            // Comment syntaxes are checked before the generic markup branch
            // so "<!--" stays a comment in XML/HTML rather than a tag.
            if (language.getLineComment() != null
                    && text.startsWith(language.getLineComment(), i)) {
                kind = TokenKind.COMMENT;
                end = i;
                while (end < n && text.charAt(end) != '\n') {
                    end++;
                }
            } else if (language.getBlockOpen() != null
                    && text.startsWith(language.getBlockOpen(), i)) {
                kind = TokenKind.COMMENT;
                int close = text.indexOf(language.getBlockClose(),
                        i + language.getBlockOpen().length());
                end = (close < 0) ? n : close + language.getBlockClose().length();
            } else if (language.isMarkup() && c == '<') {
                kind = (text.startsWith("<!", i) || text.startsWith("<?", i))
                        ? TokenKind.DECLARATION
                        : TokenKind.TAG;
                end = closeMarkup(text, i);
            } else if (language.hasPreprocessor() && c == '#'
                    && (i == 0 || text.charAt(i - 1) == '\n')) {
                kind = TokenKind.DIRECTIVE;
                end = i;
                while (end < n && text.charAt(end) != '\n') {
                    end++;
                }
            } else if (language.getStringQuotes().indexOf(c) >= 0) {
                kind = TokenKind.STRING;
                end = i + 1;
                while (end < n) {
                    char s = text.charAt(end);
                    if (s == '\\' && end + 1 < n) {
                        end += 2;
                        continue;
                    }
                    end++;
                    if (s == c) {
                        break;
                    }
                }
                if (end > n) {
                    end = n;
                }
            } else if (isNumberStart(text, i)) {
                kind = TokenKind.NUMBER;
                end = i;
                while (end < n) {
                    char d = text.charAt(end);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                        end++;
                    } else if ((d == '+' || d == '-') && end > i
                            && "eEpPxX".indexOf(text.charAt(end - 1)) >= 0) {
                        end++;
                    } else {
                        break;
                    }
                }
            } else if (isIdentifierStart(c)) {
                int wordEnd = i;
                while (wordEnd < n && isIdentifierPart(text.charAt(wordEnd))) {
                    wordEnd++;
                }
                if (isKeyword(language, text.substring(i, wordEnd))) {
                    kind = TokenKind.KEYWORD;
                    end = wordEnd;
                } else {
                    end = wordEnd; // plain word: falls through as PLAIN
                }
            }
            if (kind == null) {
                // Plain character: extend (or start) the merged plain run.
                if (plainStart < 0) {
                    plainStart = i;
                }
                // Merge a whole plain word at once to keep the loop cheap.
                if (isIdentifierStart(c)) {
                    while (i < n && isIdentifierPart(text.charAt(i))) {
                        i++;
                    }
                } else {
                    i++;
                }
                continue;
            }
            if (plainStart >= 0) {
                out.add(new Token(plainStart, i - plainStart, TokenKind.PLAIN));
                plainStart = -1;
            }
            out.add(new Token(i, end - i, kind));
            i = end;
        }
        if (plainStart >= 0) {
            out.add(new Token(plainStart, n - plainStart, TokenKind.PLAIN));
        }
        return out;
    }

    /**
     * Paints the whole document in one batch: every character first gets the
     * theme's plain style, then each non-plain token overrides its span. No-op
     * for documents above {@link #MAX_HIGHLIGHT_CHARS} (callers show them
     * uncolourised) and for {@link Languages#PLAIN}.
     */
    public static void apply(StyledDocument doc, Language lang,
            EditorTheme theme) {
        if (doc == null || theme == null) {
            return;
        }
        int length = doc.getLength();
        if (length == 0 || !shouldHighlight(length)) {
            return;
        }
        Language language = (lang != null) ? lang : Languages.PLAIN;
        Map<TokenKind, SimpleAttributeSet> styles = stylesFor(theme);
        try {
            String text = doc.getText(0, length);
            doc.setCharacterAttributes(0, length,
                    styles.get(TokenKind.PLAIN), true);
            if (language == Languages.PLAIN) {
                return;
            }
            for (Token token : tokenize(text, language)) {
                if (token.kind() != TokenKind.PLAIN) {
                    doc.setCharacterAttributes(token.start(), token.length(),
                            styles.get(token.kind()), true);
                }
            }
        } catch (javax.swing.text.BadLocationException ble) {
            // The document cannot shrink under us on the EDT; stay unstyled.
        }
    }

    /** Builds the per-kind character styles (colour, bold keywords). */
    private static Map<TokenKind, SimpleAttributeSet> stylesFor(
            EditorTheme theme) {
        Map<TokenKind, SimpleAttributeSet> styles =
                new EnumMap<>(TokenKind.class);
        for (TokenKind kind : TokenKind.values()) {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, theme.colorFor(kind));
            if (kind == TokenKind.KEYWORD) {
                StyleConstants.setBold(attrs, true);
            }
            styles.put(kind, attrs);
        }
        return styles;
    }

    /** The offset just past the {@code >} closing a markup construct. */
    private static int closeMarkup(String text, int from) {
        int i = from + 1;
        int n = text.length();
        char quote = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static boolean isNumberStart(String text, int i) {
        char c = text.charAt(i);
        if (Character.isDigit(c)) {
            return true;
        }
        return c == '.' && i + 1 < text.length()
                && Character.isDigit(text.charAt(i + 1));
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Case-sensitive keyword test, with a case-insensitive retry so uppercase
     * catalogues (SQL) also match the lower-case spellings people type.
     */
    private static boolean isKeyword(Language lang, String word) {
        if (!lang.hasKeywords()) {
            return false;
        }
        return lang.getKeywords().contains(word)
                || lang.getKeywords().contains(word.toUpperCase(Locale.ROOT));
    }
}
