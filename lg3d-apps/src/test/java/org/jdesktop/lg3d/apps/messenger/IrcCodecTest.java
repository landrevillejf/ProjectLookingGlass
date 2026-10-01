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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link IrcCodec}: the pure CTCP / mIRC-formatting /
 * line-splitting helpers used by the native IRC backend. They assert CTCP
 * framing and parsing, that formatting and colour codes are stripped for the
 * plain Swing transcript, and that an outgoing body is chunked on UTF-8 byte
 * length so no wire line exceeds the RFC 2812 512-octet cap and no multi-byte
 * code point is split. No socket is touched.
 */
class IrcCodecTest {

    @Test
    @DisplayName("isCtcp recognises only a fully delimited payload")
    void detectsCtcp() {
        assertTrue(IrcCodec.isCtcp(IrcCodec.wrapCtcp("ACTION", "waves")));
        assertTrue(IrcCodec.isCtcp("\u0001VERSION\u0001"));
        assertFalse(IrcCodec.isCtcp("plain text"));
        assertFalse(IrcCodec.isCtcp("\u0001half"));
        assertFalse(IrcCodec.isCtcp(null));
        assertFalse(IrcCodec.isCtcp("\u0001"));
    }

    @Test
    @DisplayName("wrapCtcp frames a command and its argument")
    void wrapsCtcp() {
        assertEquals("\u0001ACTION waves\u0001", IrcCodec.wrapCtcp("ACTION", "waves"));
        // An empty argument adds no trailing space.
        assertEquals("\u0001VERSION\u0001", IrcCodec.wrapCtcp("VERSION", ""));
        assertEquals("\u0001PING\u0001", IrcCodec.wrapCtcp("PING", null));
    }

    @Test
    @DisplayName("parseCtcp splits [COMMAND, argument] and upper-cases the verb")
    void parsesCtcp() {
        String[] action = IrcCodec.parseCtcp(IrcCodec.wrapCtcp("ACTION", "waves hello"));
        assertEquals("ACTION", action[0]);
        assertEquals("waves hello", action[1]);

        String[] version = IrcCodec.parseCtcp("\u0001VERSION\u0001");
        assertEquals("VERSION", version[0]);
        assertEquals("", version[1]);

        String[] lower = IrcCodec.parseCtcp("\u0001ping 123\u0001");
        assertEquals("PING", lower[0]);
        assertEquals("123", lower[1]);
    }

    @Test
    @DisplayName("parseCtcp of a non-CTCP string returns an empty verb")
    void parsesNonCtcp() {
        String[] r = IrcCodec.parseCtcp("hello");
        assertEquals("", r[0]);
        assertEquals("hello", r[1]);
        assertEquals("", IrcCodec.parseCtcp(null)[1]);
    }

    @Test
    @DisplayName("stripFormatting removes bold/italic/underline/reverse/reset")
    void stripsSimpleFormatting() {
        assertEquals("hi", IrcCodec.stripFormatting("\u0002hi\u0002"));
        assertEquals("hi", IrcCodec.stripFormatting("\u001dhi\u001f"));
        assertEquals("hi", IrcCodec.stripFormatting("\u0016hi\u000f"));
        assertEquals("hello", IrcCodec.stripFormatting("hello"));
        assertEquals("", IrcCodec.stripFormatting(null));
        assertEquals("", IrcCodec.stripFormatting(""));
    }

    @Test
    @DisplayName("stripFormatting consumes colour codes with an optional background")
    void stripsColour() {
        assertEquals("red", IrcCodec.stripFormatting("\u00034red\u0003"));
        assertEquals("x", IrcCodec.stripFormatting("\u00034,5x\u0003"));
        assertEquals("two digits", IrcCodec.stripFormatting("\u000312,07two digits\u0003"));
    }

    @Test
    @DisplayName("splitMessage returns one wire line for a short body")
    void splitsShortMessage() {
        List<String> lines = IrcCodec.splitMessage("PRIVMSG", "#lg3d", "hi");
        assertEquals(1, lines.size());
        assertEquals("PRIVMSG #lg3d :hi", lines.get(0));
    }

    @Test
    @DisplayName("splitMessage of a null body yields a single bare header line")
    void splitsNullMessage() {
        List<String> lines = IrcCodec.splitMessage("PRIVMSG", "#lg3d", null);
        assertEquals(1, lines.size());
        assertEquals("PRIVMSG #lg3d :", lines.get(0));
    }

    @Test
    @DisplayName("splitMessage chunks a long body under the 512-octet cap")
    void splitsLongMessage() {
        String body = "a".repeat(2000);
        List<String> lines = IrcCodec.splitMessage("PRIVMSG", "#lg3d", body);
        assertTrue(lines.size() > 1, "a 2000-char body must span several lines");
        StringBuilder reassembled = new StringBuilder();
        String head = "PRIVMSG #lg3d :";
        for (String line : lines) {
            // Each line, once the CR-LF terminator is added by the writer, must
            // fit the RFC cap; the codec budgets for those two octets already.
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length
                            <= IrcCodec.MAX_LINE_OCTETS - 2,
                    "wire line exceeds the octet budget: " + line.length());
            assertTrue(line.startsWith(head));
            reassembled.append(line.substring(head.length()));
        }
        assertEquals(body, reassembled.toString());
    }

    @Test
    @DisplayName("splitMessage never splits a multi-byte code point")
    void splitsMultiByteSafely() {
        // U+1F600 is 4 UTF-8 bytes and a surrogate pair in Java (2 chars).
        String body = "\uD83D\uDE00".repeat(200);
        List<String> lines = IrcCodec.splitMessage("PRIVMSG", "#lg3d", body);
        assertTrue(lines.size() > 1);
        String head = "PRIVMSG #lg3d :";
        StringBuilder reassembled = new StringBuilder();
        for (String line : lines) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length
                    <= IrcCodec.MAX_LINE_OCTETS - 2);
            reassembled.append(line.substring(head.length()));
        }
        assertEquals(body, reassembled.toString());
    }
}
