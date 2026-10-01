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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link IrcMessage}: the pure RFC 2812 line parser/formatter
 * at the heart of the native IRC backend. They assert prefix/command/param
 * decomposition, the {@code nick!user@host} split, numeric detection, trailing
 * parameters that contain spaces, tolerance of null/blank/malformed lines, and
 * the {@code toLine()} round trip (a body with spaces is re-emitted as a
 * {@code :trailing} token). No socket is touched.
 */
class IrcMessageTest {

    @Test
    @DisplayName("a full PRIVMSG line splits into prefix, command and params")
    void parsesPrivmsg() {
        IrcMessage m = IrcMessage.parse(":alice!user@host.example PRIVMSG #lg3d :hello world");
        assertEquals("alice!user@host.example", m.getPrefix());
        assertEquals("PRIVMSG", m.getCommand());
        assertEquals("#lg3d", m.param(0));
        assertEquals("hello world", m.trailing());
        assertEquals("alice", m.getNick());
        assertEquals("user", m.getUser());
        assertEquals("host.example", m.getHost());
        assertFalse(m.isNumeric());
        assertTrue(m.is("privmsg"));
    }

    @Test
    @DisplayName("a numeric reply is detected and its params are indexed")
    void parsesNumeric() {
        IrcMessage m = IrcMessage.parse(":server.example 001 alice :Welcome to the network");
        assertEquals("001", m.getCommand());
        assertTrue(m.isNumeric());
        assertEquals("alice", m.param(0));
        assertEquals("Welcome to the network", m.trailing());
        // A bare server prefix has no nick!user@host split.
        assertEquals("server.example", m.getNick());
        assertEquals("", m.getUser());
        assertEquals("", m.getHost());
    }

    @Test
    @DisplayName("the namreply numeric keeps middle params and a spaced trailing")
    void parsesNamReply() {
        IrcMessage m = IrcMessage.parse(":server 353 alice = #lg3d :alice @bob +carol");
        assertEquals("353", m.getCommand());
        assertEquals("alice", m.param(0));
        assertEquals("=", m.param(1));
        assertEquals("#lg3d", m.param(2));
        assertEquals("alice @bob +carol", m.trailing());
    }

    @Test
    @DisplayName("a command with only a trailing param (PING) parses")
    void parsesPing() {
        IrcMessage m = IrcMessage.parse("PING :server.example");
        assertEquals("PING", m.getCommand());
        assertNull(m.getPrefix());
        assertEquals("server.example", m.trailing());
    }

    @Test
    @DisplayName("the command verb is upper-cased")
    void upperCasesCommand() {
        IrcMessage m = IrcMessage.parse("privmsg #a :b");
        assertEquals("PRIVMSG", m.getCommand());
    }

    @Test
    @DisplayName("null and blank lines parse to null")
    void parsesNullAndBlank() {
        assertNull(IrcMessage.parse(null));
        assertNull(IrcMessage.parse(""));
        assertNull(IrcMessage.parse("   "));
        assertNull(IrcMessage.parse("\r"));
    }

    @Test
    @DisplayName("a trailing carriage return is stripped before parsing")
    void stripsTrailingCr() {
        IrcMessage m = IrcMessage.parse("PING :tok\r");
        assertEquals("PING", m.getCommand());
        assertEquals("tok", m.trailing());
    }

    @Test
    @DisplayName("a command with no params yields an empty param list")
    void parsesBareCommand() {
        IrcMessage m = IrcMessage.parse("QUIT");
        assertEquals("QUIT", m.getCommand());
        assertTrue(m.getParams().isEmpty());
        assertEquals("", m.trailing());
        assertEquals("", m.param(0));
    }

    @Test
    @DisplayName("a trailing token may itself begin with a colon")
    void parsesColonInTrailing() {
        IrcMessage m = IrcMessage.parse(":n PRIVMSG #c ::leading");
        assertEquals(":leading", m.trailing());
    }

    @Test
    @DisplayName("of(...).toLine() round-trips a body with spaces as :trailing")
    void roundTripsSpacedBody() {
        String line = IrcMessage.of("PRIVMSG", "#lg3d", "hello there").toLine();
        assertEquals("PRIVMSG #lg3d :hello there", line);
        assertEquals("hello there", IrcMessage.parse(line).trailing());
    }

    @Test
    @DisplayName("of(...).toLine() emits a single-word param without a colon")
    void roundTripsSingleWord() {
        assertEquals("JOIN #lg3d", IrcMessage.of("JOIN", "#lg3d").toLine());
    }

    @Test
    @DisplayName("of(...).toLine() emits an empty trailing param as a bare colon")
    void roundTripsEmptyTrailing() {
        assertEquals("PART #lg3d :", IrcMessage.of("PART", "#lg3d", "").toLine());
    }

    @Test
    @DisplayName("of(...) null-coerces params and upper-cases the command")
    void ofIsDefensive() {
        IrcMessage m = IrcMessage.of("notice", null, (String) null);
        assertEquals("NOTICE", m.getCommand());
        assertEquals("", m.param(0));
        assertEquals("", m.param(1));
    }

    @Test
    @DisplayName("is(cmd) is case-insensitive and false for a mismatch")
    void isIsCaseInsensitive() {
        IrcMessage m = IrcMessage.parse("JOIN #x");
        assertTrue(m.is("join"));
        assertTrue(m.is("JOIN"));
        assertFalse(m.is("part"));
    }
}
