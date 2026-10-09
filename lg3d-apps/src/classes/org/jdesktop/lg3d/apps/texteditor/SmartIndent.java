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
 * Pure auto-indent logic: given the text of the line the caret is on, compute
 * the indent the next line should get. The rule set is deliberately the
 * smallest one that covers every built-in {@link Language}:
 *
 * <ol>
 *   <li>the new line inherits the current line's leading whitespace;</li>
 *   <li>one extra level is added when the trimmed line ends with an opener
 *       ({@code {}, (, [, :} or a trailing backslash continuation).</li>
 * </ol>
 *
 * <p>A level is {@code tabSize} spaces, or a single tab when the settings ask
 * for hard tabs. No Swing involved, so the whole behaviour is testable.</p>
 */
public final class SmartIndent {

    /** Characters that open an indented block when they end a line. */
    private static final String OPENERS = "{([:";

    private SmartIndent() {
        // Static utility.
    }

    /** The leading whitespace of a line (spaces and tabs only). */
    public static String indentOf(String line) {
        if (line == null) {
            return "";
        }
        int i = 0;
        while (i < line.length()
                && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /** One indent level for the given settings: N spaces, or a tab. */
    public static String level(int tabSize, boolean hardTabs) {
        if (hardTabs) {
            return "\t";
        }
        int size = Math.max(1, Math.min(tabSize, 32));
        return " ".repeat(size);
    }

    /**
     * The indent for a line inserted after {@code currentLine}: its own
     * indent, plus one level when the trimmed line ends with a block opener
     * or a continuation backslash.
     */
    public static String nextLineIndent(String currentLine, int tabSize,
            boolean hardTabs) {
        if (currentLine == null) {
            return "";
        }
        String indent = indentOf(currentLine);
        String trimmed = currentLine.trim();
        if (trimmed.isEmpty()) {
            return indent;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (OPENERS.indexOf(last) >= 0 || last == '\\') {
            return indent + level(tabSize, hardTabs);
        }
        return indent;
    }

    /** True when {@code ch} is a bracket the editor match-highlights. */
    public static boolean isBracket(char ch) {
        return ch == '(' || ch == ')' || ch == '[' || ch == ']'
                || ch == '{' || ch == '}';
    }

    /** The partner of a bracket character, or 0 when not a bracket. */
    public static char pairOf(char bracket) {
        return switch (bracket) {
            case '(' -> ')';
            case ')' -> '(';
            case '[' -> ']';
            case ']' -> '[';
            case '{' -> '}';
            case '}' -> '{';
            default -> (char) 0;
        };
    }
}
