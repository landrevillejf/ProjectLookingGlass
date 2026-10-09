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

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable colour scheme for the editor: the chrome colours (background,
 * text, caret, selection, gutter, current line, bracket match) plus one colour
 * per {@link TokenKind}. Two themes ship built in &mdash; {@link #LIGHT} and
 * {@link #DARK} &mdash; selected by name through {@link #byName}; extensions
 * can construct their own.
 */
public final class EditorTheme {

    /** The classic paper-white editing scheme. */
    public static final EditorTheme LIGHT = new EditorTheme("Light",
            new Color(0xFF, 0xFF, 0xFF), new Color(0x1E, 0x1E, 0x1E),
            new Color(0x1E, 0x1E, 0x1E), new Color(0xB4, 0xD5, 0xFE),
            new Color(0xF2, 0xF2, 0xF2), new Color(0x6A, 0x6A, 0x6A),
            new Color(0xE8, 0xF0, 0xFE), new Color(0xFF, 0xE0, 0x8A),
            tokenColors(
                    new Color(0x1E, 0x1E, 0x1E),   // PLAIN
                    new Color(0x00, 0x33, 0xB3),   // KEYWORD (blue)
                    new Color(0x06, 0x7D, 0x17),   // STRING (green)
                    new Color(0x8C, 0x8C, 0x8C),   // COMMENT (grey)
                    new Color(0x17, 0x50, 0xEB),   // NUMBER (bright blue)
                    new Color(0x9C, 0x27, 0xB0),   // DIRECTIVE (purple)
                    new Color(0x00, 0x69, 0x8C),   // TAG (teal)
                    new Color(0x7A, 0x5B, 0x00))); // DECLARATION (bronze)

    /** A low-glare dark scheme for evening sessions. */
    public static final EditorTheme DARK = new EditorTheme("Dark",
            new Color(0x1E, 0x1F, 0x22), new Color(0xDC, 0xDC, 0xDC),
            new Color(0xFF, 0xFF, 0xFF), new Color(0x2E, 0x4A, 0x6E),
            new Color(0x2B, 0x2C, 0x30), new Color(0x8A, 0x8A, 0x8A),
            new Color(0x2A, 0x33, 0x42), new Color(0x5C, 0x50, 0x22),
            tokenColors(
                    new Color(0xDC, 0xDC, 0xDC),   // PLAIN
                    new Color(0x56, 0x9C, 0xD6),   // KEYWORD (soft blue)
                    new Color(0xCE, 0x91, 0x78),   // STRING (salmon)
                    new Color(0x6A, 0x99, 0x55),   // COMMENT (sage)
                    new Color(0xB5, 0xCE, 0xA8),   // NUMBER (pale green)
                    new Color(0xC5, 0x86, 0xC0),   // DIRECTIVE (orchid)
                    new Color(0x4E, 0xC9, 0xB0),   // TAG (turquoise)
                    new Color(0xDC, 0xD7, 0x8A))); // DECLARATION (wheat)

    private static final List<EditorTheme> BUILT_IN =
            Collections.unmodifiableList(Arrays.asList(LIGHT, DARK));

    private final String name;
    private final Color background;
    private final Color foreground;
    private final Color caret;
    private final Color selection;
    private final Color gutterBackground;
    private final Color gutterForeground;
    private final Color currentLine;
    private final Color bracketMatch;
    private final Map<TokenKind, Color> tokens;

    /**
     * Creates a theme. The {@code tokenColors} map must carry an entry for
     * every {@link TokenKind}; missing kinds fall back to {@code foreground}.
     */
    public EditorTheme(String name, Color background, Color foreground,
            Color caret, Color selection, Color gutterBackground,
            Color gutterForeground, Color currentLine, Color bracketMatch,
            Map<TokenKind, Color> tokenColors) {
        this.name = name;
        this.background = background;
        this.foreground = foreground;
        this.caret = caret;
        this.selection = selection;
        this.gutterBackground = gutterBackground;
        this.gutterForeground = gutterForeground;
        this.currentLine = currentLine;
        this.bracketMatch = bracketMatch;
        Map<TokenKind, Color> copy = new EnumMap<>(TokenKind.class);
        for (TokenKind kind : TokenKind.values()) {
            Color colour = tokenColors.get(kind);
            copy.put(kind, (colour != null) ? colour : foreground);
        }
        this.tokens = Collections.unmodifiableMap(copy);
    }

    /** The built-in themes, Light first. */
    public static List<EditorTheme> builtIn() {
        return BUILT_IN;
    }

    /** The theme with the given name (case-insensitive), defaulting to Light. */
    public static EditorTheme byName(String name) {
        if (name != null) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            for (EditorTheme theme : BUILT_IN) {
                if (theme.name.toLowerCase(Locale.ROOT).equals(key)) {
                    return theme;
                }
            }
        }
        return LIGHT;
    }

    private static Map<TokenKind, Color> tokenColors(Color plain, Color keyword,
            Color string, Color comment, Color number, Color directive,
            Color tag, Color declaration) {
        Map<TokenKind, Color> map = new EnumMap<>(TokenKind.class);
        map.put(TokenKind.PLAIN, plain);
        map.put(TokenKind.KEYWORD, keyword);
        map.put(TokenKind.STRING, string);
        map.put(TokenKind.COMMENT, comment);
        map.put(TokenKind.NUMBER, number);
        map.put(TokenKind.DIRECTIVE, directive);
        map.put(TokenKind.TAG, tag);
        map.put(TokenKind.DECLARATION, declaration);
        return map;
    }

    /** The display name ("Light" / "Dark"). */
    public String getName() {
        return name;
    }

    public Color getBackground() {
        return background;
    }

    public Color getForeground() {
        return foreground;
    }

    public Color getCaret() {
        return caret;
    }

    public Color getSelection() {
        return selection;
    }

    public Color getGutterBackground() {
        return gutterBackground;
    }

    public Color getGutterForeground() {
        return gutterForeground;
    }

    public Color getCurrentLine() {
        return currentLine;
    }

    public Color getBracketMatch() {
        return bracketMatch;
    }

    /** The colour for a token kind; never null. */
    public Color colorFor(TokenKind kind) {
        return tokens.get(kind);
    }

    @Override
    public String toString() {
        return name;
    }
}
