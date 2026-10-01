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
import java.util.Collections;
import java.util.List;

/**
 * One parsed IRC protocol line (RFC 2812 grammar):
 * <pre>
 *   message    =  [ ":" prefix SPACE ] command [ params ]
 *   prefix     =  servername / ( nickname [ "!" user ] [ "@" host ] )
 *   params     =  *13( SPACE middleparam ) [ SPACE ":" trailingparam ]
 * </pre>
 *
 * <p>This is a pure value type with no I/O: {@link #parse(String)} and
 * {@link #toLine()} are the single, well-tested point where wire text becomes
 * structure and back, so the rest of the client never hand-splits a line.</p>
 */
public final class IrcMessage {

    private final String prefix;              // may be null
    private final String command;             // upper-cased verb or numeric
    private final List<String> params;        // middle params + optional trailing

    private IrcMessage(String prefix, String command, List<String> params) {
        this.prefix = prefix;
        this.command = command;
        this.params = Collections.unmodifiableList(new ArrayList<>(params));
    }

    /** Builds a message to send (no prefix; the server adds ours). */
    public static IrcMessage of(String command, String... params) {
        List<String> p = new ArrayList<>();
        if (params != null) {
            for (String s : params) {
                p.add(s == null ? "" : s);
            }
        }
        return new IrcMessage(null, upper(command), p);
    }

    /**
     * Parses one IRC line. Returns {@code null} for a null/blank line. The
     * parser is tolerant: a malformed prefix (missing {@code :}) is treated as
     * the command, and everything after the first {@code :} in the param list is
     * a single trailing parameter (which may contain spaces).
     *
     * @param line the raw line, without the CR/LF terminator
     * @return the parsed message, or null
     */
    public static IrcMessage parse(String line) {
        if (line == null) {
            return null;
        }
        String s = line;
        // Strip a trailing CR (the reader usually already removed the newline).
        if (s.endsWith("\r")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isBlank()) {
            return null;
        }
        String prefix = null;
        int idx = 0;
        if (s.charAt(0) == ':') {
            int sp = s.indexOf(' ', 1);
            if (sp < 0) {
                // A bare ":prefix" with no command; treat the whole thing as prefix.
                return new IrcMessage(s.substring(1), "", List.of());
            }
            prefix = s.substring(1, sp);
            idx = sp + 1;
        }
        // Command token.
        int sp = s.indexOf(' ', idx);
        String command;
        if (sp < 0) {
            command = s.substring(idx);
            return new IrcMessage(prefix, upper(command), List.of());
        }
        command = s.substring(idx, sp);
        idx = sp + 1;

        List<String> params = new ArrayList<>();
        while (idx < s.length()) {
            // Skip runs of spaces between params.
            while (idx < s.length() && s.charAt(idx) == ' ') {
                idx++;
            }
            if (idx >= s.length()) {
                break;
            }
            if (s.charAt(idx) == ':') {
                params.add(s.substring(idx + 1));
                break;
            }
            int next = s.indexOf(' ', idx);
            if (next < 0) {
                params.add(s.substring(idx));
                break;
            }
            params.add(s.substring(idx, next));
            idx = next + 1;
        }
        return new IrcMessage(prefix, upper(command), params);
    }

    private static String upper(String s) {
        return (s == null) ? "" : s.toUpperCase(java.util.Locale.ROOT);
    }

    public String getPrefix() { return prefix; }
    public String getCommand() { return command; }
    public List<String> getParams() { return params; }

    /** The parameter at {@code i}, or {@code ""} if absent. */
    public String param(int i) {
        return (i >= 0 && i < params.size()) ? params.get(i) : "";
    }

    /** The trailing (last) parameter, conventionally the message text. */
    public String trailing() {
        return params.isEmpty() ? "" : params.get(params.size() - 1);
    }

    /** True if the command is a three-digit numeric reply. */
    public boolean isNumeric() {
        if (command.length() != 3) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(command.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** True if the command equals {@code cmd} (case-insensitive). */
    public boolean is(String cmd) {
        return command.equals(upper(cmd));
    }

    /** The nickname portion of the prefix ({@code nick!user@host}), else the prefix. */
    public String getNick() {
        if (prefix == null) {
            return "";
        }
        int bang = prefix.indexOf('!');
        return (bang >= 0) ? prefix.substring(0, bang) : prefix;
    }

    /** The {@code user} portion of a {@code nick!user@host} prefix, else "". */
    public String getUser() {
        if (prefix == null) {
            return "";
        }
        int bang = prefix.indexOf('!');
        int at = prefix.indexOf('@');
        if (bang >= 0 && at > bang) {
            return prefix.substring(bang + 1, at);
        }
        return "";
    }

    /** The {@code host} portion of a {@code nick!user@host} prefix, else "". */
    public String getHost() {
        if (prefix == null) {
            return "";
        }
        int at = prefix.indexOf('@');
        return (at >= 0) ? prefix.substring(at + 1) : "";
    }

    /**
     * Serialises back to the wire form. The final parameter is emitted as a
     * {@code :trailing} token so bodies with spaces survive the round trip.
     *
     * @return the line, without a terminator
     */
    public String toLine() {
        StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            sb.append(':').append(prefix).append(' ');
        }
        sb.append(command);
        for (int i = 0; i < params.size(); i++) {
            boolean last = (i == params.size() - 1);
            String p = params.get(i);
            sb.append(' ');
            if (last && (p.isEmpty() || p.indexOf(' ') >= 0 || p.charAt(0) == ':')) {
                sb.append(':');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toLine();
    }
}
