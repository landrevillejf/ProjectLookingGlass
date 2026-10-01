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
package org.jdesktop.lg3d.apps.messenger;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure IRC text helpers: CTCP framing (RFC-style {@code \u0001}-delimited
 * extended commands such as {@code ACTION} and {@code VERSION}), mIRC
 * formatting-code stripping, and 512-byte line splitting. Like
 * {@link IrcMessage}, this class does no I/O and is exhaustively unit-tested.
 */
public final class IrcCodec {

    /** The CTCP delimiter (0x01). */
    public static final char CTCP = '\u0001';

    /** mIRC formatting control codes. */
    private static final char BOLD = '\u0002';
    private static final char COLOR = '\u0003';
    private static final char ITALIC = '\u001d';
    private static final char UNDERLINE = '\u001f';
    private static final char REVERSE = '\u0016';
    private static final char RESET = '\u000f';

    /** RFC 2812 caps a line at 512 octets including the CR-LF terminator. */
    public static final int MAX_LINE_OCTETS = 512;
    private static final int USABLE_OCTETS = MAX_LINE_OCTETS - 2;

    private IrcCodec() {
        // no instances
    }

    /** True if {@code text} is a CTCP-quoted payload. */
    public static boolean isCtcp(String text) {
        return text != null && text.length() >= 2
                && text.charAt(0) == CTCP
                && text.charAt(text.length() - 1) == CTCP;
    }

    /**
     * Wraps a CTCP command (and optional argument) in the {@code \u0001}
     * delimiters.
     *
     * @param command the CTCP verb, e.g. {@code ACTION}
     * @param arg     the argument, may be empty
     * @return the framed payload
     */
    public static String wrapCtcp(String command, String arg) {
        StringBuilder sb = new StringBuilder();
        sb.append(CTCP).append(command == null ? "" : command);
        if (arg != null && !arg.isEmpty()) {
            sb.append(' ').append(arg);
        }
        sb.append(CTCP);
        return sb.toString();
    }

    /**
     * Splits a CTCP payload into {@code [command, argument]}. For a non-CTCP
     * string it returns {@code ["", text]}.
     *
     * @param text the payload
     * @return a two-element array
     */
    public static String[] parseCtcp(String text) {
        if (!isCtcp(text)) {
            return new String[]{"", text == null ? "" : text};
        }
        String inner = text.substring(1, text.length() - 1);
        int sp = inner.indexOf(' ');
        if (sp < 0) {
            return new String[]{inner.toUpperCase(java.util.Locale.ROOT), ""};
        }
        return new String[]{
                inner.substring(0, sp).toUpperCase(java.util.Locale.ROOT),
                inner.substring(sp + 1)};
    }

    /**
     * Removes mIRC formatting codes (bold, colour, italic, underline, reverse,
     * reset) so text renders cleanly in a plain Swing transcript. Colour codes
     * consume their one- or two-digit foreground and optional {@code ,bg} pair.
     *
     * @param text the raw text
     * @return the de-formatted text
     */
    public static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case BOLD, ITALIC, UNDERLINE, REVERSE, RESET -> {
                    // dropped
                }
                case COLOR -> {
                    // Skip up to two digits, then an optional ",bb".
                    int n = 0;
                    while (n < 2 && i + 1 < text.length()
                            && Character.isDigit(text.charAt(i + 1))) {
                        i++;
                        n++;
                    }
                    if (n > 0 && i + 1 < text.length() && text.charAt(i + 1) == ',') {
                        int j = i + 2;
                        int m = 0;
                        while (m < 2 && j < text.length() && Character.isDigit(text.charAt(j))) {
                            j++;
                            m++;
                        }
                        if (m > 0) {
                            i = j - 1;
                        }
                    }
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Splits an outgoing {@code PRIVMSG}/{@code NOTICE} body into as many wire
     * lines as the 512-octet limit requires, breaking on UTF-8 byte length (not
     * char count) so multi-byte text is never truncated mid-sequence at the
     * framing level. Each returned element is a complete line ready to send.
     *
     * @param verb   {@code PRIVMSG} or {@code NOTICE}
     * @param target the channel or nick
     * @param text   the message body
     * @return the ordered wire lines (never empty for non-null text)
     */
    public static List<String> splitMessage(String verb, String target, String text) {
        List<String> out = new ArrayList<>();
        String safeText = (text == null) ? "" : text;
        String head = verb + " " + target + " :";
        int overhead = head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int budget = Math.max(1, USABLE_OCTETS - overhead);

        int start = 0;
        while (start < safeText.length()) {
            int end = chunkEnd(safeText, start, budget);
            out.add(head + safeText.substring(start, end));
            start = end;
        }
        if (out.isEmpty()) {
            out.add(head);
        }
        return out;
    }

    /** Finds the char index (<= start+budgetChars) whose UTF-8 bytes fit budget. */
    private static int chunkEnd(String s, int start, int budget) {
        int bytes = 0;
        int i = start;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cc = Character.charCount(cp);
            int len = utf8Len(cp);
            if (bytes + len > budget) {
                break;
            }
            bytes += len;
            i += cc;
        }
        // Guarantee forward progress even if a single code point exceeds budget.
        return (i == start) ? Math.min(s.length(), start + 1) : i;
    }

    private static int utf8Len(int cp) {
        if (cp < 0x80) {
            return 1;
        }
        if (cp < 0x800) {
            return 2;
        }
        if (cp < 0x10000) {
            return 3;
        }
        return 4;
    }
}
