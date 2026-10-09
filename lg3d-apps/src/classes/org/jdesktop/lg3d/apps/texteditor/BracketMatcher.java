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

/**
 * Pure bracket-matching for the caret highlighter: given a caret anchor, find
 * the bracket it sits on (the character at the anchor, else the one just
 * before it &mdash; the way every editor behaves right after you type) and
 * return its partner's offset.
 *
 * <p>The scan is nesting-aware and language-aware: brackets inside string
 * literals and comments do not count, using the same declarative comment and
 * quote syntax as {@link SyntaxHighlighter}. A malformed document (an
 * unclosed string swallowing the rest of the file) simply yields "no match"
 * rather than a wrong one.</p>
 */
public final class BracketMatcher {

    /** A scan guard so a pathological 10 MB document cannot block the EDT. */
    private static final int MAX_SCAN_CHARS = 1_000_000;

    private BracketMatcher() {
        // Static utility.
    }

    /**
     * The offset of the bracket matching the one at (or just before)
     * {@code anchor}, or -1 when there is none. {@code lang} may be null for
     * a syntax-blind scan.
     */
    public static int match(String text, int anchor, Language lang) {
        if (text == null || text.isEmpty() || anchor < 0
                || anchor > text.length()) {
            return -1;
        }
        char here = (anchor < text.length()) ? text.charAt(anchor) : 0;
        char before = (anchor > 0) ? text.charAt(anchor - 1) : 0;
        if (SmartIndent.isBracket(here)) {
            return scan(text, anchor, here, lang);
        }
        if (SmartIndent.isBracket(before)) {
            return scan(text, anchor - 1, before, lang);
        }
        return -1;
    }

    private static int scan(String text, int at, char bracket, Language lang) {
        char partner = SmartIndent.pairOf(bracket);
        boolean forward = isOpening(bracket);
        int depth = 0;
        int limit = Math.min(text.length(), at + MAX_SCAN_CHARS);
        int floor = Math.max(0, at - MAX_SCAN_CHARS);
        int i = at;
        while (forward ? i < limit : i >= floor) {
            char c = text.charAt(i);
            if (c == bracket && !insideLiteral(text, i, lang)) {
                depth++;
            } else if (c == partner && !insideLiteral(text, i, lang)) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i += forward ? 1 : -1;
        }
        return -1;
    }

    private static boolean isOpening(char bracket) {
        return bracket == '(' || bracket == '[' || bracket == '{';
    }

    /**
     * True when offset {@code i} sits inside a string literal or comment.
     * Decided by a fast re-scan of the enclosing line (strings in the built-in
     * languages, except JS/Go backticks and shell here-docs, do not span
     * lines; block comments are detected by an unclosed opener search).
     */
    private static boolean insideLiteral(String text, int i, Language lang) {
        if (lang == null) {
            return false;
        }
        int lineStart = text.lastIndexOf('\n', i - 1) + 1;
        String quotes = lang.getStringQuotes();
        char quote = 0;
        for (int p = lineStart; p < i; p++) {
            char c = text.charAt(p);
            if (quote != 0) {
                if (c == '\\') {
                    p++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (quotes.indexOf(c) >= 0) {
                quote = c;
            } else if (lang.getLineComment() != null
                    && text.startsWith(lang.getLineComment(), p)) {
                return true; // rest of the line is a comment
            }
        }
        if (quote != 0) {
            return true;
        }
        // Block comment: the nearest opener before i has no closer before i.
        String open = lang.getBlockOpen();
        String close = lang.getBlockClose();
        if (open != null && close != null) {
            int openAt = text.lastIndexOf(open, i);
            if (openAt >= 0) {
                int closeAt = text.lastIndexOf(close, i - 1);
                if (closeAt < openAt && openAt + open.length() <= i) {
                    return true;
                }
            }
        }
        return false;
    }
}
